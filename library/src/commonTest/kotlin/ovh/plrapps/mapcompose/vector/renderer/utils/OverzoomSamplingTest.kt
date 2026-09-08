package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [sampleWindow], which resolves which samples of an overzoomed source raster one map
 * tile magnifies.
 *
 * The interesting case is deep overzoom: the window is narrower than one sample there, which the
 * integer crop this replaced resolved to zero -- a 256-sample source disappeared nine levels above
 * its `maxzoom`.
 */
class OverzoomSamplingTest {

    /** Where the centre of the `k`-th sample of [window] lands, in device pixels. */
    private fun centreOf(window: SampleWindow, k: Int): Double =
        window.origin + (k + 0.5) * window.scale.toDouble()

    @Test
    fun `a source at the requested zoom is the whole tile`() {
        val window = sampleWindow(dim = 256, sub = 0, span = 1, canvasSize = 512)

        assertEquals(0, window.first)
        assertEquals(255, window.last)
        assertEquals(256, window.count)
        assertEquals(0f, window.origin)
        assertEquals(2f, window.scale)
    }

    @Test
    fun `a sub-square is magnified over the whole tile`() {
        // Two levels of overzoom: this map tile is the third of four columns.
        val window = sampleWindow(dim = 256, sub = 2, span = 4, canvasSize = 512)

        // 64 samples over 512 pixels.
        assertEquals(8f, window.scale)
        // The sub-square is samples 128..191, and the two straddling the tile's centre must sit
        // half a sample either side of it.
        assertEquals(252.0, centreOf(window, 159 - window.first), absoluteTolerance = 1e-6)
        assertEquals(260.0, centreOf(window, 160 - window.first), absoluteTolerance = 1e-6)
        assertTrue(window.first <= 128, "the western boundary sample is included (${window.first})")
        assertTrue(window.last >= 191, "so is the eastern one (${window.last})")
    }

    @Test
    fun `neighbouring sub-squares share their boundary samples`() {
        val west = sampleWindow(dim = 256, sub = 0, span = 4, canvasSize = 512)
        val east = sampleWindow(dim = 256, sub = 1, span = 4, canvasSize = 512)

        // The half-sample margin either side is what lets the bilinear filter run straight across
        // the boundary instead of clamping at it, as upstream's single texture does.
        assertTrue(
            east.first <= west.last,
            "the two windows must overlap (${west.first}..${west.last} and ${east.first}..${east.last})",
        )
    }

    @Test
    fun `a sample keeps its place on the tile whichever window holds it`() {
        val west = sampleWindow(dim = 256, sub = 0, span = 4, canvasSize = 512)
        val east = sampleWindow(dim = 256, sub = 1, span = 4, canvasSize = 512)

        // Sample 64 is the first of the eastern sub-square. It is drawn by both windows -- once
        // just off the western tile's right edge and once just inside the eastern tile's left one
        // -- and the two placements must be exactly one tile apart, which is what makes the
        // magnification continuous across the boundary.
        assertEquals(
            centreOf(east, 64 - east.first) + 512.0,
            centreOf(west, 64 - west.first),
            absoluteTolerance = 1e-6,
        )
    }

    @Test
    fun `a window narrower than one sample still names samples`() {
        // Eight levels past a 4-sample source: the map tile covers a sixty-fourth of one sample.
        val window = sampleWindow(dim = 4, sub = 100, span = 256, canvasSize = 512)

        assertTrue(window.count >= 1, "the source must not disappear (${window.count} samples)")
        assertTrue(window.first in 0..3 && window.last in 0..3, "and must stay in range ($window)")
        // The tile sits inside sample 1: 100 / 256 of the source is 1.5625 samples in.
        assertTrue(window.first <= 1 && window.last >= 1, "around the sample it covers ($window)")
    }

    @Test
    fun `a window narrower than one sample is still positioned`() {
        val dim = 4
        val span = 256
        val canvasSize = 512

        // Two adjacent map tiles this deep differ by one 256th of the source, so their windows must
        // differ by one tile width -- which is what the integer crop could not express at all.
        val left = sampleWindow(dim = dim, sub = 100, span = span, canvasSize = canvasSize)
        val right = sampleWindow(dim = dim, sub = 101, span = span, canvasSize = canvasSize)

        val sharedSample = maxOf(left.first, right.first)
        assertEquals(
            canvasSize.toDouble(),
            centreOf(left, sharedSample - left.first) - centreOf(right, sharedSample - right.first),
            absoluteTolerance = 1e-3,
        )
    }

    @Test
    fun `the last sub-square stops at the last sample`() {
        val window = sampleWindow(dim = 4, sub = 255, span = 256, canvasSize = 512)

        assertEquals(3, window.last, "never past the tile's own samples -- the border ring is not shadeable")
        assertTrue(window.first <= 3)
    }

    @Test
    fun `tileFractionOf places a sample across the tile`() {
        val window = sampleWindow(dim = 4, sub = 0, span = 1, canvasSize = 400)

        // Four samples over the tile: their centres sit at 1/8, 3/8, 5/8, 7/8.
        assertEquals(0.125, window.tileFractionOf(0), absoluteTolerance = 1e-9)
        assertEquals(0.875, window.tileFractionOf(3), absoluteTolerance = 1e-9)
    }
}
