package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The distance transform a locally drawn glyph goes through, upstream's `tiny-sdf`. */
class TinySdfTest {

    private val buffer = 3
    private val radius = 8.0
    private val cutoff = 0.25

    /** A solid square of ink, the simplest shape with an inside and an outside. */
    private fun square(size: Int): IntArray = IntArray(size * size) { 255 }

    private fun render(alpha: IntArray, width: Int, height: Int): ByteArray =
        TinySdf.render(alpha, width, height, buffer, radius, cutoff)

    private fun ByteArray.at(x: Int, y: Int, width: Int): Int = (this[y * width + x].toInt() and 0xFF)

    @Test
    fun `the field reaches the buffer past the ink on every side`() {
        val field = render(square(8), 8, 8)
        assertEquals((8 + 2 * buffer) * (8 + 2 * buffer), field.size)
    }

    @Test
    fun `an empty box has no field at all`() {
        val field = render(IntArray(4 * 4), 4, 4)
        assertTrue(field.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun `a box with no extent is the padding and nothing else`() {
        // Upstream allocates the padded buffer and returns it untouched, which is what a glyph with
        // no ink -- a space -- carries.
        val field = render(IntArray(0), 0, 0)
        assertEquals((2 * buffer) * (2 * buffer), field.size)
        assertTrue(field.all { (it.toInt() and 0xFF) == 0 })
    }

    @Test
    fun `the inside is high the edge is the cutoff and the outside is low`() {
        val size = 12
        val width = size + 2 * buffer
        val field = render(square(size), size, size)

        val inside = field.at(width / 2, width / 2, width)
        val edge = field.at(buffer, width / 2, width)
        val outside = field.at(0, width / 2, width)

        // Upstream's encoding: `base = 255 * (1 - cutoff)` is the value of the edge itself.
        val base = (255 * (1 - cutoff)).toInt()
        assertTrue(inside > base, "inside $inside should be past the edge value $base")
        assertTrue(edge in (base - 40)..(base + 40), "edge $edge should be near $base")
        assertTrue(outside < base, "outside $outside should be below the edge value $base")
    }

    @Test
    fun `the field falls off as it leaves the ink`() {
        val size = 12
        val width = size + 2 * buffer
        val field = render(square(size), size, size)

        val row = width / 2
        var previous = Int.MAX_VALUE
        for (x in buffer downTo 0) {
            val value = field.at(x, row, width)
            assertTrue(value <= previous, "the field should not rise as it leaves the ink")
            previous = value
        }
    }

    @Test
    fun `the field is symmetric about a symmetric shape`() {
        val size = 10
        val width = size + 2 * buffer
        val field = render(square(size), size, size)

        for (index in 0 until width) {
            assertEquals(
                field.at(index, 0, width),
                field.at(index, width - 1, width),
                "row $index should read the same from either side",
            )
            assertEquals(
                field.at(0, index, width),
                field.at(width - 1, index, width),
                "column $index should read the same from either side",
            )
        }
    }

    @Test
    fun `partial coverage sits between covered and empty`() {
        val size = 6
        val width = size + 2 * buffer
        val solid = render(square(size), size, size)
        val half = render(IntArray(size * size) { 128 }, size, size)

        val centre = width / 2
        assertTrue(
            half.at(centre, centre, width) < solid.at(centre, centre, width),
            "half coverage should read as less inside the shape than full coverage",
        )
    }
}
