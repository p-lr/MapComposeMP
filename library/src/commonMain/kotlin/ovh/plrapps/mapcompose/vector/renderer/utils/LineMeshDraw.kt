package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * Draws a tessellated line ribbon.
 *
 * Three things here are load-bearing and were each established by `DrawVerticesProbeTest`:
 *
 * - **`isAntiAlias = false`.** The mesh carries `line.fragment.glsl`'s own antialiasing in its
 *   per-vertex alpha, and Skia antialiases each triangle independently -- leaving it on draws a
 *   seam along every edge two triangles share.
 * - **A white paint colour.** With no shader, Skia modulates the vertex colours by the paint's own
 *   colour, which defaults to opaque black; a default paint renders the whole ribbon black.
 * - **No shader in the paint.** That is what makes the blend mode irrelevant, which matters because
 *   Compose's Android actual drops the `blendMode` argument altogether
 *   (`AndroidCanvas.android.kt`, upstream `TODO(njawad)`). It is also why `line-pattern` does not
 *   come through here.
 *
 * Alpha is folded into the vertex colours rather than into `Paint.alpha`, matching the shader's
 * `fragColor = color * (alpha * opacity)`.
 *
 * On Android these tiles are rasterized into an `ImageBitmap`, so the canvas underneath is a
 * *software* `android.graphics.Canvas` -- `drawVertices` is unsupported only on the
 * hardware-accelerated one.
 */
internal fun DrawScope.drawLineMesh(mesh: LineMesh, color: Color, opacity: Float) {
    if (opacity <= 0f || mesh.indexCount == 0) return
    drawMesh(mesh, ColorArrayView(mesh, opacity, color, null))
}

/** As [drawLineMesh], with each vertex coloured by `line-gradient` at its own line progress. */
internal fun DrawScope.drawLineMesh(mesh: LineMesh, opacity: Float, colorAt: (Float) -> Color) {
    if (opacity <= 0f || mesh.indexCount == 0) return
    drawMesh(mesh, ColorArrayView(mesh, opacity, null, colorAt))
}

private fun DrawScope.drawMesh(mesh: LineMesh, colors: List<Color>) {
    val positions = OffsetArrayView(mesh)
    drawIntoCanvas { canvas ->
        canvas.drawVertices(
            Vertices(
                vertexMode = VertexMode.Triangles,
                positions = positions,
                // Unused without a shader, but the constructor requires the same length.
                textureCoordinates = positions,
                colors = colors,
                indices = IntArrayView(mesh.indices, mesh.indexCount),
            ),
            BlendMode.Modulate,
            Paint().apply {
                color = Color.White
                isAntiAlias = false
            },
        )
    }
}

/*
 * `Vertices` takes lists and copies them into primitive arrays in its constructor. Views over the
 * mesh's own arrays keep the boxes alive only for the length of that copy, rather than building a
 * throwaway `ArrayList` of tens of thousands of `Offset`s per feature.
 */

private class OffsetArrayView(private val mesh: LineMesh) : AbstractList<Offset>() {
    override val size: Int get() = mesh.vertexCount
    override fun get(index: Int): Offset =
        Offset(mesh.positions[2 * index], mesh.positions[2 * index + 1])
}

private class ColorArrayView(
    private val mesh: LineMesh,
    private val opacity: Float,
    private val flat: Color?,
    private val colorAt: ((Float) -> Color)?,
) : AbstractList<Color>() {
    override val size: Int get() = mesh.vertexCount
    override fun get(index: Int): Color {
        val base = flat ?: colorAt!!(mesh.progress[index])
        return base.copy(alpha = base.alpha * mesh.alphas[index] * opacity)
    }
}

private class IntArrayView(private val values: IntArray, override val size: Int) : AbstractList<Int>() {
    override fun get(index: Int): Int = values[index]
}
