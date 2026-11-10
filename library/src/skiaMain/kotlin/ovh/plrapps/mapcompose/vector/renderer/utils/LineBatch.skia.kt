package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

/**
 * Skia's `drawVertices` takes whole arrays -- their sizes are its counts -- so a batch that does not
 * fill its arrays is copied into ones from [scratch] that it very nearly fills, and the rest padded.
 */
internal actual fun DrawScope.drawTriangles(
    positions: FloatArray,
    colors: IntArray,
    vertexCount: Int,
    indices: ShortArray,
    indexCount: Int,
    scratch: TriangleScratch,
) {
    if (indexCount == 0) return
    val sizeClass = scratch.vertexClass(vertexCount)
    val drawnPositions = scratch.positions(sizeClass)
    val drawnColors = scratch.colors(sizeClass)
    positions.copyInto(drawnPositions, endIndex = vertexCount * 2)
    colors.copyInto(drawnColors, endIndex = vertexCount)
    val drawnIndices = scratch.indices(indexCount)
    indices.copyInto(drawnIndices, endIndex = indexCount)
    /* Vertices past the count are referenced by nothing. Indices past it must name *something*:
     * all zero, they are triangles of one vertex three times, which cover no pixel. */
    drawnIndices.fill(0, indexCount, drawnIndices.size)
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawVertices(
            VertexMode.TRIANGLES,
            drawnPositions,
            drawnColors,
            null,
            drawnIndices,
            BlendMode.MODULATE,
            scratch.paint,
        )
    }
}

/**
 * Arrays for [drawTriangles] to hand Skia, by size class: each class is twice the last, so a draw
 * pads at most as much again as it carries and the whole set is twice the largest.
 *
 * Trimming with `copyOf` instead, a fresh array per draw, was the largest single source of garbage
 * in a tile render -- three arrays per style layer, each the size of the layer's whole mesh.
 */
internal actual class TriangleScratch actual constructor() {
    private val positionArrays = arrayOfNulls<FloatArray>(CLASS_COUNT)
    private val colorArrays = arrayOfNulls<IntArray>(CLASS_COUNT)
    private val indexArrays = arrayOfNulls<ShortArray>(CLASS_COUNT)

    val paint: Paint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = false
    }

    fun vertexClass(vertexCount: Int): Int = classOf(vertexCount, SMALLEST_VERTICES)

    fun positions(sizeClass: Int): FloatArray =
        positionArrays[sizeClass] ?: FloatArray((SMALLEST_VERTICES shl sizeClass) * 2).also { positionArrays[sizeClass] = it }

    fun colors(sizeClass: Int): IntArray =
        colorArrays[sizeClass] ?: IntArray(SMALLEST_VERTICES shl sizeClass).also { colorArrays[sizeClass] = it }

    /** A whole number of triangles, at least [indexCount] indices long. */
    fun indices(indexCount: Int): ShortArray {
        val sizeClass = classOf(indexCount, SMALLEST_INDICES)
        return indexArrays[sizeClass] ?: ShortArray(SMALLEST_INDICES shl sizeClass).also { indexArrays[sizeClass] = it }
    }

    private fun classOf(count: Int, smallest: Int): Int {
        var sizeClass = 0
        while ((smallest.toLong() shl sizeClass) < count) sizeClass++
        return sizeClass
    }

    private companion object {
        const val SMALLEST_VERTICES = 64
        /** A multiple of three, so that every class holds whole triangles. */
        const val SMALLEST_INDICES = 192
        const val CLASS_COUNT = 24
    }
}
