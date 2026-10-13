package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

/** Skia's `drawVertices` takes whole arrays, so a batch that is not full is trimmed to size first. */
internal actual fun DrawScope.drawTriangles(
    positions: FloatArray,
    colors: IntArray,
    vertexCount: Int,
    indices: ShortArray,
    indexCount: Int,
) {
    if (indexCount == 0) return
    val paint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = false
    }
    try {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawVertices(
                VertexMode.TRIANGLES,
                if (positions.size == vertexCount * 2) positions else positions.copyOf(vertexCount * 2),
                if (colors.size == vertexCount) colors else colors.copyOf(vertexCount),
                null,
                if (indices.size == indexCount) indices else indices.copyOf(indexCount),
                BlendMode.MODULATE,
                paint,
            )
        }
    } finally {
        paint.close()
    }
}
