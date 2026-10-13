package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.renderer.pixelAt
import ovh.plrapps.mapcompose.vector.renderer.renderToBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [drawTriangles] keeps the three guarantees `DrawVerticesProbeTest` pins for Compose's `Vertices`
 * path, and [LineBatch] draws what it is given, where it is given it, however it splits the calls.
 */
class LineBatchTest {

    /** A square of two triangles, [color] at every vertex but [edgeAlpha] on the right-hand pair. */
    private fun square(x: Float, y: Float, size: Float, edgeAlpha: Float = 1f) = LineMesh(
        positions = floatArrayOf(x, y, x + size, y, x, y + size, x + size, y + size),
        alphas = floatArrayOf(1f, edgeAlpha, 1f, edgeAlpha),
        progress = FloatArray(4),
        indices = intArrayOf(0, 1, 2, 1, 3, 2),
        vertexCount = 4,
        indexCount = 6,
    )

    @Test
    fun `vertex colours are used as they are and interpolated`() {
        val bitmap = renderToBitmap(size = 64) {
            drawTriangles(
                positions = floatArrayOf(0f, 0f, 64f, 0f, 0f, 64f, 64f, 64f),
                colors = intArrayOf(Color.Red.toArgb(), Color.Blue.toArgb(), Color.Red.toArgb(), Color.Blue.toArgb()),
                vertexCount = 4,
                indices = shortArrayOf(0, 1, 2, 1, 3, 2),
                indexCount = 6,
            )
        }
        val left = bitmap.pixelAt(2, 32)
        val middle = bitmap.pixelAt(32, 32)
        assertTrue(left.red > 0.9f && left.blue < 0.1f, "the left edge keeps its vertex colour: $left")
        assertTrue(middle.red in 0.35f..0.65f && middle.blue in 0.35f..0.65f, "the middle is a blend: $middle")
    }

    @Test
    fun `two triangles sharing an edge leave no seam`() {
        val bitmap = renderToBitmap(size = 64) {
            val batch = LineBatch()
            batch.add(this, square(0f, 0f, 64f), 1f, Color.Red, null, 0f, 0f)
            batch.flush(this)
        }
        for (i in 0 until 64) assertColorEquals(Color.Red, bitmap.pixelAt(i, i), message = "diagonal pixel $i")
    }

    @Test
    fun `meshes keep their own colour, opacity and translation in one batch`() {
        val bitmap = renderToBitmap(size = 64) {
            val batch = LineBatch()
            batch.add(this, square(0f, 0f, 16f), 1f, Color.Red, null, 0f, 0f)
            batch.add(this, square(0f, 0f, 16f), 0.5f, Color.Blue, null, 32f, 32f)
            batch.add(this, square(32f, 0f, 16f), 1f, null, { Color.Green }, 0f, 0f)
            batch.flush(this)
        }
        assertColorEquals(Color.Red, bitmap.pixelAt(8, 8))
        assertColorEquals(Color.Blue.copy(alpha = 0.5f), bitmap.pixelAt(40, 40))
        assertColorEquals(Color.Green, bitmap.pixelAt(40, 8))
        assertEquals(0f, bitmap.pixelAt(8, 40).alpha, "nothing where nothing was added")
    }

    @Test
    fun `vertex alpha scales the colour's own alpha`() {
        val bitmap = renderToBitmap(size = 64) {
            val batch = LineBatch()
            batch.add(this, square(0f, 0f, 64f, edgeAlpha = 0f), 1f, Color.Red, null, 0f, 0f)
            batch.flush(this)
        }
        assertTrue(bitmap.pixelAt(1, 32).alpha > 0.9f)
        assertTrue(bitmap.pixelAt(62, 32).alpha < 0.1f)
    }

    @Test
    fun `a batch past the vertex limit is split and drawn whole`() {
        // Far more vertices than one call may index; every square must still land.
        val bitmap = renderToBitmap(size = 64) {
            val batch = LineBatch()
            repeat(MAX_VERTEX_ARRAY_LENGTH / 4 + 100) { batch.add(this, square(0f, 0f, 1f), 1f, Color.Red, null, 0f, 0f) }
            batch.add(this, square(40f, 40f, 8f), 1f, Color.Blue, null, 0f, 0f)
            batch.flush(this)
        }
        assertColorEquals(Color.Red, bitmap.pixelAt(0, 0))
        assertColorEquals(Color.Blue, bitmap.pixelAt(44, 44))
    }
}
