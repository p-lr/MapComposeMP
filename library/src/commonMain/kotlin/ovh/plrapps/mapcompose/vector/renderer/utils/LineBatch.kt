package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * A batch also owns the [builder] its meshes come from and the [TriangleScratch] they are drawn
 * through, so that one object carries every array a tile's lines need -- which is what lets
 * [LineBatchPool] hand the whole set from one render to the next.
 */
internal class LineBatch {
    private var positions = FloatArray(INITIAL_VERTICES * 2)
    private var colors = IntArray(INITIAL_VERTICES)
    private var indices = ShortArray(INITIAL_VERTICES * 3)
    private var vertexCount = 0
    private var indexCount = 0
    private var translateX = 0f
    private var translateY = 0f
    private val scratch = TriangleScratch()

    /* What [builder]'s sink colours the chunk it is handed with: the feature being tessellated,
     * set by [target]. */
    private var targetScope: DrawScope? = null
    private var targetOpacity = 1f
    private var targetColor = Color.Unspecified
    private var targetColorAt: ((Float) -> Color)? = null
    private var targetDx = 0f
    private var targetDy = 0f

    /**
     * Tessellates into this batch: every chunk it finishes is [add]ed with what [target] last set.
     * One builder serves every line layer of a render, because its arrays and its cached
     * cross-sections are worth keeping across them.
     */
    val builder = LineMeshBuilder { mesh ->
        addMesh(targetScope!!, mesh, targetOpacity, targetColor, targetColorAt, targetDx, targetDy)
    }

    /**
     * How the chunks [builder] produces from here on are drawn, until [release]: in [color], or per
     * vertex by [colorAt] when there is one. The colour is not nullable so that it is never boxed --
     * this is called once per feature.
     */
    fun target(scope: DrawScope, opacity: Float, color: Color, colorAt: ((Float) -> Color)?, dx: Float, dy: Float) {
        targetScope = scope
        targetOpacity = opacity
        targetColor = color
        targetColorAt = colorAt
        targetDx = dx
        targetDy = dy
    }

    /** Lets go of what [target] was given; the draw scope must not outlive its feature. */
    fun release() {
        targetScope = null
        targetColorAt = null
    }

    /** Forgets anything pending, for a batch whose render ended early and is about to be reused. */
    fun reset() {
        release()
        builder.reset()
        vertexCount = 0
        indexCount = 0
    }

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
        if (color != null) {
            addMesh(scope, mesh, opacity, color, null, dx, dy)
        } else {
            addMesh(scope, mesh, opacity, Color.Unspecified, colorAt!!, dx, dy)
        }
    }

    private fun addMesh(
        scope: DrawScope,
        mesh: LineMesh,
        opacity: Float,
        color: Color,
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
        if (colorAt == null && color.colorSpace == ColorSpaces.Srgb) {
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
                val c = if (colorAt != null) colorAt(mesh.progress[i]) else color
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
                scope.drawTriangles(positions, colors, vertexCount, indices, indexCount, scratch)
            } else {
                scope.translate(translateX, translateY) {
                    drawTriangles(positions, colors, vertexCount, indices, indexCount, scratch)
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
    scratch: TriangleScratch,
)

/**
 * What a platform's [drawTriangles] keeps between calls, owned by the [LineBatch] that draws through
 * it and so used by one render at a time.
 */
internal expect class TriangleScratch()

/**
 * The line batches no render is using.
 *
 * A batch grows to the largest style layer it has drawn -- a few hundred kilobytes of vertex arrays
 * on a dense tile -- and a render used to build one from nothing and drop it. Renders run a few at a
 * time and one after another for as long as the map moves, so the same few batches now go round.
 */
internal class LineBatchPool {
    private val mutex = Mutex()
    private val idle = ArrayList<LineBatch>()

    suspend fun take(): LineBatch = mutex.withLock { idle.removeLastOrNull() } ?: LineBatch()

    suspend fun give(batch: LineBatch) {
        batch.reset()
        mutex.withLock { if (idle.size < MAX_IDLE) idle.add(batch) }
    }

    suspend fun clear() = mutex.withLock { idle.clear() }

    private companion object {
        /** More than this many idle at once means a burst of concurrency that has passed. */
        const val MAX_IDLE = 8
    }
}
