package ovh.plrapps.mapcompose.vector.symbol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fade a symbol runs when placement changes its mind about it.
 *
 * [OpacityState.create] is a transcription of upstream's `OpacityState` constructor
 * (`maplibre-gl-js/src/symbol/placement.ts`); [OpacityState.alphaAt] is this port's own, and is the
 * divergence documented on the class -- upstream holds the committed opacity until its next commit,
 * which it makes every frame.
 */
class OpacityStateTest {

    /** Upstream's `u_fade_change` after [elapsedMs] of a fade -- see [PlacementResult.fadeChangeAt]. */
    private fun fadeChange(elapsedMs: Long, zoomAdjustment: Double = 0.0): Float =
        (elapsedMs.toDouble() / SYMBOL_FADE_DURATION_MS + zoomAdjustment).toFloat()

    @Test
    fun `a newly placed symbol starts invisible and fades in`() {
        val state = OpacityState.create(previous = null, increment = 1f, placed = true)

        assertEquals(0f, state.opacity)
        assertEquals(0f, state.alphaAt(fadeChange(0)))
        assertEquals(0.5f, state.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS / 2)), 1e-4f)
        assertEquals(1f, state.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS)))
        assertEquals(
            1f, state.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS * 10)),
            "the fade stops at fully opaque",
        )
    }

    @Test
    fun `skipFade admits a symbol at full opacity`() {
        /* Upstream's reason: the symbol is outside the viewport but inside the collision index's
         * padding, so it is not on screen yet and has nothing to fade in from. */
        val state = OpacityState.create(previous = null, increment = 1f, placed = true, skipFade = true)

        assertEquals(1f, state.opacity)
        assertEquals(1f, state.alphaAt(fadeChange(0)))
    }

    @Test
    fun `skipFade does nothing for a symbol that was not placed`() {
        val state = OpacityState.create(previous = null, increment = 1f, placed = false, skipFade = true)

        assertEquals(0f, state.opacity)
    }

    @Test
    fun `a placed symbol advances by the increment rather than to it`() {
        val first = OpacityState.create(previous = null, increment = 1f, placed = true)
        val second = OpacityState.create(previous = first, increment = 0.25f, placed = true)
        val third = OpacityState.create(previous = second, increment = 0.25f, placed = true)

        assertEquals(0.25f, second.opacity, 1e-6f)
        assertEquals(0.5f, third.opacity, 1e-6f)
    }

    @Test
    fun `a symbol that loses its ground fades out from where it was`() {
        var state = OpacityState.create(previous = null, increment = 1f, placed = true)
        repeat(4) { state = OpacityState.create(previous = state, increment = 0.25f, placed = true) }
        assertEquals(1f, state.opacity, 1e-6f)

        val fading = OpacityState.create(previous = state, increment = 0.25f, placed = false)

        /* The step reads the *previous* state's `placed`, which is upstream's
         * `prevState.placed ? increment : -increment` -- so the first commit after a symbol is
         * dropped still steps upwards, and the fade out begins on the one after. */
        assertEquals(1f, fading.opacity, 1e-6f)
        assertEquals(0.5f, fading.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS / 2)), 1e-4f)
        assertEquals(0f, fading.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS)))
    }

    @Test
    fun `a fade reversed mid-flight continues from where it had got to`() {
        val half = OpacityState.create(
            previous = OpacityState.create(previous = null, increment = 1f, placed = true),
            increment = 0.5f,
            placed = true,
        )
        assertEquals(0.5f, half.opacity, 1e-6f)

        val reversed = OpacityState.create(previous = half, increment = 0f, placed = false)

        assertEquals(0.5f, reversed.opacity, 1e-6f)
        assertEquals(0.25f, reversed.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS / 4)), 1e-4f)
        assertEquals(0f, reversed.alphaAt(fadeChange(SYMBOL_FADE_DURATION_MS / 2)), 1e-4f)
    }

    @Test
    fun `a symbol is hidden only once it is both invisible and unplaced`() {
        assertTrue(OpacityState.create(null, 1f, placed = false).isHidden())
        assertFalse(OpacityState.create(null, 1f, placed = true).isHidden())
        assertFalse(
            OpacityState.create(OpacityState.create(null, 1f, placed = true), 0.5f, placed = false)
                .isHidden(),
            "still visible, so still drawn while it fades out",
        )
    }

    @Test
    fun `a joint state is hidden only when both halves are`() {
        val placedIcon = JointOpacityState.create(null, 1f, placedText = false, placedIcon = true)
        assertFalse(placedIcon.isHidden())

        val neither = JointOpacityState.create(null, 1f, placedText = false, placedIcon = false)
        assertTrue(neither.isHidden())
    }

    @Test
    fun `a zero fade duration is a step`() {
        /* `fadeChangeAt` reports a full change immediately when the duration is zero, which is
         * upstream's `symbolFadeChange` returning 1. */
        val result = PlacementResult(emptyList(), commitTime = 0L, lastPlacementChangeTime = 0L, fadeDurationMs = 0L)

        assertEquals(1f, OpacityState.create(null, 1f, placed = true).alphaAt(result.fadeChangeAt(0)))
        assertEquals(0f, OpacityState.create(null, 1f, placed = false).alphaAt(result.fadeChangeAt(0)))
    }

    @Test
    fun `zooming out since the commit advances the fade faster`() {
        /* Upstream's `symbolFadeChange` adds `prevZoomAdjustment`, whose reason is in
         * `zoomAdjustment`: "reduce the fade duration when zooming out quickly ... fading them more
         * quickly reduces the unwanted effect". */
        val state = OpacityState.create(previous = null, increment = 1f, placed = true)
        val quarter = SYMBOL_FADE_DURATION_MS / 4

        assertEquals(0.25f, state.alphaAt(fadeChange(quarter)), 1e-4f)
        assertEquals(0.75f, state.alphaAt(fadeChange(quarter, zoomAdjustment = 0.5)), 1e-4f)
    }

    @Test
    fun `the fade change is the elapsed fraction of the duration`() {
        val result = PlacementResult(emptyList(), commitTime = 0L, lastPlacementChangeTime = 0L)

        assertEquals(0f, result.fadeChangeAt(0))
        assertEquals(0.5f, result.fadeChangeAt(SYMBOL_FADE_DURATION_MS / 2), 1e-4f)
        assertEquals(1f, result.fadeChangeAt(SYMBOL_FADE_DURATION_MS), 1e-4f)
    }
}
