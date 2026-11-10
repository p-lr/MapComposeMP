package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much a `*-pattern` has to be pre-scaled in tile space to hold its size on screen.
 *
 * A tile rasterized for integer zoom `z` is drawn at `2^(actualZoom - z)` of its bitmap size --
 * `VisibleTilesResolver` rounds the level up, so it is always shrunk, never stretched. The pattern
 * is scaled by the inverse so the two cancel.
 */
class PatternScaleTest {

    @Test
    fun `a tile drawn at its own size needs no extra scale`() {
        assertEquals(1f, patternZoomScale(tileZoom = 10.0, actualZoom = 10.0))
    }

    @Test
    fun `a tile shrunk by half doubles the pattern`() {
        assertEquals(2f, patternZoomScale(tileZoom = 11.0, actualZoom = 10.0))
    }

    @Test
    fun `the scale grows smoothly between pyramid levels`() {
        val quarter = patternZoomScale(tileZoom = 11.0, actualZoom = 10.75)
        val half = patternZoomScale(tileZoom = 11.0, actualZoom = 10.5)
        assertTrue(quarter < half, "expected $quarter < $half")
        assertTrue(half > 1f && half < 2f, "expected 1 < $half < 2")
    }

    @Test
    fun `the factor stays in one octave`() {
        // A level that is somehow far from the map zoom must not blow the pattern bitmap up.
        assertEquals(2f, patternZoomScale(tileZoom = 20.0, actualZoom = 10.0))
        assertEquals(1f, patternZoomScale(tileZoom = 5.0, actualZoom = 10.0))
    }
}
