package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipLineTest {

    @Test
    fun `a line inside the box is returned whole`() {
        val clipped = clipLine(listOf(listOf(10f to 10f, 20f to 20f, 30f to 10f)), 0f, 0f, 100f, 100f)
        assertEquals(1, clipped.size)
        assertEquals(listOf(10f to 10f, 20f to 20f, 30f to 10f), clipped[0])
    }

    @Test
    fun `a line leaving the box stops on the edge`() {
        val clipped = clipLine(listOf(listOf(50f to 50f, 150f to 50f)), 0f, 0f, 100f, 100f)
        assertEquals(1, clipped.size)
        assertEquals(listOf(50f to 50f, 100f to 50f), clipped[0])
    }

    @Test
    fun `a line entering the box starts on the edge`() {
        val clipped = clipLine(listOf(listOf(-50f to 50f, 50f to 50f)), 0f, 0f, 100f, 100f)
        assertEquals(1, clipped.size)
        assertEquals(listOf(0f to 50f, 50f to 50f), clipped[0])
    }

    @Test
    fun `a line fully outside the box yields nothing`() {
        val clipped = clipLine(listOf(listOf(200f to 200f, 300f to 300f)), 0f, 0f, 100f, 100f)
        assertTrue(clipped.isEmpty())
    }

    @Test
    fun `a line that leaves and re-enters yields two runs`() {
        val clipped = clipLine(
            listOf(listOf(50f to 50f, 150f to 50f, 150f to 70f, 50f to 70f)),
            0f, 0f, 100f, 100f,
        )
        assertEquals(2, clipped.size)
        assertEquals(listOf(50f to 50f, 100f to 50f), clipped[0])
        assertEquals(listOf(100f to 70f, 50f to 70f), clipped[1])
    }
}
