package ovh.plrapps.mapcompose.vector.renderer.utils

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * A tile is rasterized into an `ImageBitmap`, so the canvas here is the *software*
 * `android.graphics.Canvas`, where `drawVertices` is supported. Unlike Skia's it takes counts, so
 * nothing is copied.
 */
internal actual fun DrawScope.drawTriangles(
    positions: FloatArray,
    colors: IntArray,
    vertexCount: Int,
    indices: ShortArray,
    indexCount: Int,
) {
    if (indexCount == 0) return
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = false
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            vertexCount * 2,
            positions,
            0,
            null,
            0,
            colors,
            0,
            indices,
            0,
            indexCount,
            paint,
        )
    }
}
