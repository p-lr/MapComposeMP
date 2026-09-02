package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineShadingTest {

    @Test
    fun `inset and outset match the vertex shader without a gap`() {
        // width 4, dpr 1: halfwidth 2, ANTIALIASING 0.5, no gap.
        assertEquals(0f, lineInset(gapWidth = 0f, devicePixelRatio = 1f))
        assertEquals(2.5f, lineOutset(gapWidth = 0f, width = 4f, devicePixelRatio = 1f))
    }

    @Test
    fun `inset and outset match the vertex shader with a gap`() {
        // The gap-width test fixture: width 4, gap 12, dpr 1.
        assertEquals(6.5f, lineInset(gapWidth = 12f, devicePixelRatio = 1f))
        assertEquals(10.5f, lineOutset(gapWidth = 12f, width = 4f, devicePixelRatio = 1f))
    }

    @Test
    fun `a zero-width line gets no antialias padding`() {
        // Upstream's `(halfwidth == 0.0 ? 0.0 : ANTIALIASING)`.
        assertEquals(0f, lineOutset(gapWidth = 0f, width = 0f, devicePixelRatio = 1f))
    }

    @Test
    fun `antialias padding shrinks on a dense screen`() {
        assertEquals(0.5f, antialiasing(1f))
        assertEquals(0.25f, antialiasing(2f))
        assertEquals(2.25f, lineOutset(gapWidth = 0f, width = 4f, devicePixelRatio = 2f))
    }

    @Test
    fun `a sharp line is opaque to within one pixel of its edge`() {
        val outset = lineOutset(gapWidth = 0f, width = 8f, devicePixelRatio = 1f)
        val blur2 = blur2(blur = 0f, devicePixelRatio = 1f)

        assertEquals(1f, lineAlpha(0f, outset, inset = 0f, blur2 = blur2), "the centre")
        assertEquals(1f, lineAlpha(outset - blur2, outset, 0f, blur2), "one pixel in from the edge")
        assertEquals(0.5f, lineAlpha(outset - blur2 / 2f, outset, 0f, blur2), "half way through the feather")
        assertEquals(0f, lineAlpha(outset, outset, 0f, blur2), "the edge itself")
        assertEquals(0f, lineAlpha(outset + 1f, outset, 0f, blur2), "beyond the edge")
    }

    @Test
    fun `a blur wider than the line fades it rather than widening it`() {
        // line-blur never enters `outset`: upstream softens the line within its own width.
        val outset = lineOutset(gapWidth = 0f, width = 4f, devicePixelRatio = 1f)
        val blur2 = blur2(blur = 6f, devicePixelRatio = 1f)

        assertEquals(2.5f, outset)
        assertEquals(7f, blur2)
        assertTrue(abs(lineAlpha(0f, outset, 0f, blur2) - 2.5f / 7f) < 1e-6f, "the centre is faint")
        assertEquals(0f, lineAlpha(outset, outset, 0f, blur2), "and the edge is still the edge")
    }

    @Test
    fun `a gapped line is transparent through its core`() {
        val inset = lineInset(gapWidth = 12f, devicePixelRatio = 1f)
        val outset = lineOutset(gapWidth = 12f, width = 4f, devicePixelRatio = 1f)
        val blur2 = blur2(blur = 0f, devicePixelRatio = 1f)

        assertEquals(0f, lineAlpha(0f, outset, inset, blur2), "the middle is the gap")
        assertEquals(0f, lineAlpha(inset - blur2, outset, inset, blur2), "the inner feather starts")
        assertEquals(1f, lineAlpha(inset, outset, inset, blur2), "and completes at the inset")
        assertEquals(1f, lineAlpha(8f, outset, inset, blur2), "the casing itself")
        assertEquals(0f, lineAlpha(outset, outset, inset, blur2), "the outer edge")
    }

    @Test
    fun `the rings span the ribbon symmetrically`() {
        val rings = alphaRings(outset = 2.5f, inset = 0f, blur2 = 1f)

        assertEquals(-1f, rings.first())
        assertEquals(1f, rings.last())
        assertTrue(rings.any { it == 0f }, "the centre line is always a ring")
        for (i in 1 until rings.size) {
            assertTrue(rings[i] > rings[i - 1], "rings are ascending: ${rings.toList()}")
        }
        for (i in rings.indices) {
            assertTrue(
                abs(rings[i] + rings[rings.size - 1 - i]) < 1e-6f,
                "rings mirror around the centre: ${rings.toList()}"
            )
        }
    }

    @Test
    fun `the rings are the breakpoints of the alpha ramp`() {
        val outset = 10.5f
        val inset = 6.5f
        val blur2 = 1f
        val rings = alphaRings(outset, inset, blur2)

        // Interpolating alpha between neighbouring rings must match evaluating it directly.
        for (i in 1 until rings.size) {
            val a = rings[i - 1] * outset
            val b = rings[i] * outset
            val alphaA = lineAlpha(abs(a), outset, inset, blur2)
            val alphaB = lineAlpha(abs(b), outset, inset, blur2)
            for (step in 1 until 8) {
                val t = step / 8f
                val dist = abs(a + (b - a) * t)
                val interpolated = alphaA + (alphaB - alphaA) * t
                assertTrue(
                    abs(interpolated - lineAlpha(dist, outset, inset, blur2)) < 1e-4f,
                    "the ramp is linear between rings ${rings[i - 1]} and ${rings[i]} at t=$t"
                )
            }
        }
    }

    @Test
    fun `a collapsed ribbon still yields usable rings`() {
        val rings = alphaRings(outset = 0f, inset = 0f, blur2 = 1f)
        assertEquals(listOf(-1f, 0f, 1f), rings.toList())
    }
}
