package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb

/**
 * One style layer's line meshes, drawn with as few `drawVertices` calls as their size allows.
 *
 * Each feature used to be its own call, through Compose's `Vertices`, whose constructor takes lists:
 * an `Offset` and a `Color` boxed per vertex, plus a `Vertices` and a `Paint` per mesh. Here meshes
 * are appended into primitive arrays -- positions, final ARGB colours, `Short` indices -- and handed
 * to [drawTriangles] once the batch is full, its translation changes, or the layer ends ([flush]).
 *
 * Batching is invisible in the result: a single call composites its triangles in order, exactly as
 * consecutive calls did. What must be respected is that anything else the layer draws -- a patterned
 * line's `drawPath` -- comes *after* [flush], or it would jump ahead of meshes still pending.
 *
 * The colour is resolved per vertex as `color * (alpha * opacity)`,
 * which is `line.fragment.glsl`'s `fragColor = color * (alpha * opacity)`.
 */
internal class LineBatch {
    private var positions = FloatArray(INITIAL_VERTICES * 2)
    private var colors = IntArray(INITIAL_VERTICES)
    private var indices = ShortArray(INITIAL_VERTICES * 3)
    private var vertexCount = 0
    private var indexCount = 0
    private var translateX = 0f
    private var translateY = 0f

    /**
     * Appends [mesh] at the translation ([dx], [dy]), coloured [color] -- or, for `line-gradient`,
     * per vertex by [colorAt] at the vertex's line progress -- at [opacity].
     */
    fun add(
        scope: DrawScope,
        mesh: LineMesh,
        opacity: Float,
        color: Color?,
        colorAt: ((Float) -> Color)?,
        dx: Float,
        dy: Float,
    ) {
        if (opacity <= 0f || mesh.indexCount == 0) return
        if (vertexCount > 0 && (dx != translateX || dy != translateY)) flush(scope)
        if (vertexCount + mesh.vertexCount > MAX_VERTEX_ARRAY_LENGTH) flush(scope)
        translateX = dx
        translateY = dy
        reserve(mesh.vertexCount, mesh.indexCount)

        val base = vertexCount
        mesh.positions.copyInto(positions, destinationOffset = 2 * base, endIndex = 2 * mesh.vertexCount)
        if (color != null && color.colorSpace == ColorSpaces.Srgb) {
            /* The flat-colour case, by integer arithmetic: an sRGB `Color` holds 8-bit channels, and
             * `copy(alpha = a).toArgb()` keeps the colour's own bytes and rounds `a * 255` -- which
             * is all this does, without a `Color` per vertex. */
            val rgb = color.toArgb() and 0x00FFFFFF
            val colorAlpha = color.alpha
            for (i in 0 until mesh.vertexCount) {
                colors[base + i] = flatVertexArgb(rgb, colorAlpha, mesh.alphas[i], opacity)
            }
        } else {
            for (i in 0 until mesh.vertexCount) {
                val c = color ?: colorAt!!(mesh.progress[i])
                colors[base + i] = c.copy(alpha = c.alpha * mesh.alphas[i] * opacity).toArgb()
            }
        }
        for (j in 0 until mesh.indexCount) {
            indices[indexCount + j] = (mesh.indices[j] + base).toShort()
        }
        vertexCount += mesh.vertexCount
        indexCount += mesh.indexCount
    }

    /** Draws whatever is pending. Must be called before the layer draws anything else, and at its end. */
    fun flush(scope: DrawScope) {
        if (indexCount > 0) {
            if (translateX == 0f && translateY == 0f) {
                scope.drawTriangles(positions, colors, vertexCount, indices, indexCount)
            } else {
                scope.translate(translateX, translateY) {
                    drawTriangles(positions, colors, vertexCount, indices, indexCount)
                }
            }
        }
        vertexCount = 0
        indexCount = 0
    }

    private fun reserve(vertices: Int, indexCount: Int) {
        val neededVertices = vertexCount + vertices
        if (neededVertices * 2 > positions.size) {
            val size = maxOf(neededVertices, colors.size * 2)
            positions = positions.copyOf(size * 2)
            colors = colors.copyOf(size)
        }
        val neededIndices = this.indexCount + indexCount
        if (neededIndices > indices.size) indices = indices.copyOf(maxOf(neededIndices, indices.size * 2))
    }

    private companion object {
        const val INITIAL_VERTICES = 4096
    }
}

/**
 * `color.copy(alpha = colorAlpha * vertexAlpha * opacity).toArgb()` for an sRGB colour whose own
 * channels are [rgb], with the very same float expression and rounding `Color` uses -- so the two are
 * identical bit for bit, which `LineBatchColorTest` checks.
 */
internal fun flatVertexArgb(rgb: Int, colorAlpha: Float, vertexAlpha: Float, opacity: Float): Int {
    val alpha = (colorAlpha * vertexAlpha * opacity * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    return (alpha shl 24) or rgb
}

/**
 * Draws [indexCount] indices' worth of triangles over the first [vertexCount] vertices, each
 * coloured by its own straight-alpha ARGB [colors] entry and nothing else.
 *
 * Three things are load-bearing, as `DrawVerticesProbeTest` established for the `Vertices` path this
 * replaces: antialiasing **off** (the mesh carries the shader's own feather, and Skia would seam every
 * shared triangle edge), a **white** paint (with no shader Skia modulates the vertex colours by it),
 * and **no shader**.
 */
internal expect fun DrawScope.drawTriangles(
    positions: FloatArray,
    colors: IntArray,
    vertexCount: Int,
    indices: ShortArray,
    indexCount: Int,
)
