package ovh.plrapps.mapcompose.vector.symbol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a frame must feed [PlacementResult.fadeChangeAt], and why it has to be that cycle's own clock.
 *
 * `SymbolComposer` draws `opacity + fadeChange`, upstream's
 * `fade_opacity[0] + u_fade_change` (`symbol_icon.vertex.glsl`). The committed opacity and the fade
 * change are two halves of one number, and they are only consistent when the second is measured from
 * the placement the first came from. A frame that pairs a freshly committed opacity with the
 * *previous* cycle's elapsed time -- which is close to a whole fade duration by the time a new
 * placement commits, since one starts at most once per fade -- draws every appearing symbol at
 * nearly full opacity for that frame and at zero on the next: the flash this pins against.
 */
class FadeClockTest {

    private val newlyPlaced = JointOpacityState.create(
        previous = null, increment = 1f, placedText = true, placedIcon = true,
    )

    private val fadingOut = JointOpacityState.create(
        previous = JointOpacityState.create(null, 1f, placedText = true, placedIcon = true, skipFade = true),
        increment = 0f, placedText = false, placedIcon = false,
    )

    private fun resultAt(commitTime: Long) =
        PlacementResult(symbols = emptyList(), commitTime = commitTime, lastPlacementChangeTime = commitTime)

    @Test
    fun `a symbol placed this cycle starts its fade at zero`() {
        val result = resultAt(1_000L)
        assertEquals(0f, newlyPlaced.text.alphaAt(result.fadeChangeAt(0L)))
        assertTrue(newlyPlaced.text.alphaAt(result.fadeChangeAt(150L)) in 0.45f..0.55f)
        assertEquals(1f, newlyPlaced.text.alphaAt(result.fadeChangeAt(SYMBOL_FADE_DURATION_MS)))
    }

    @Test
    fun `a fade change from the cycle before flashes what has just appeared`() {
        /* The state the old code could draw for one frame: the new cycle's opacities against the
         * previous cycle's elapsed time. It is not a rounding error -- it is very nearly the whole
         * fade, in both directions at once. */
        val stale = resultAt(1_000L).fadeChangeAt(SYMBOL_FADE_DURATION_MS - 10L)

        assertTrue(
            newlyPlaced.text.alphaAt(stale) > 0.9f,
            "an appearing symbol would flash in at nearly full opacity before dropping back to 0",
        )
        assertTrue(
            fadingOut.text.alphaAt(stale) < 0.1f,
            "a departing one would blink out for that frame",
        )
        assertEquals(
            1f, JointOpacityState.create(null, 1f, placedText = true, placedIcon = true, skipFade = true)
                .text.alphaAt(stale),
            "while a symbol already at full opacity is clamped, and never flickers -- which is why " +
                "only appearing labels showed it",
        )
    }

    @Test
    fun `the fade duration is what a cycle animates for when the placement changed`() {
        val result = PlacementResult(
            symbols = emptyList(), commitTime = 5_000L, lastPlacementChangeTime = 5_000L,
        )
        assertEquals(SYMBOL_FADE_DURATION_MS, result.fadeRemainingMs)
    }
}
