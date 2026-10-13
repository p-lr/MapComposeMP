package ovh.plrapps.mapcompose.vector.symbol

/**
 * How far a symbol has faded in or out, ported from `maplibre-gl-js/src/symbol/opacity_state.ts`
 * and the `OpacityState` / `JointOpacityState` constructors in `src/symbol/placement.ts`.
 *
 * [opacity] advances once per placement commit, by the fraction of the fade duration that elapsed
 * since the previous one, and [alphaAt] carries it the rest of the way at draw time. That second
 * half is upstream's too, and not an approximation of it: a placement is held for a while, so the
 * fade has to keep running between commits, and `symbol_icon.vertex.glsl` does exactly this --
 *
 * ```glsl
 * float fade_change = fade_opacity[1] > 0.5 ? u_fade_change : -u_fade_change;
 * v_total_opacity = opacity * max(0.0, min(visibility, fade_opacity[0] + fade_change));
 * ```
 *
 * -- with `u_fade_change = placement.symbolFadeChange(now())` (`render/painter.ts`), which is what
 * [PlacementResult.fadeChangeAt] supplies here.
 *
 * [placed] is the placement decision the fade is heading towards, and is deliberately read from the
 * *previous* state when advancing -- upstream's `prevState.placed ? increment : -increment`.
 */
internal class OpacityState private constructor(
    val opacity: Float,
    val placed: Boolean,
) {
    /** Upstream's `isHidden`: fully faded out and not coming back. */
    fun isHidden(): Boolean = opacity == 0f && !placed

    /**
     * This state's alpha given upstream's `u_fade_change`, which is
     * [PlacementResult.fadeChangeAt] -- the shader's
     * `fade_opacity[0] + (placed ? u_fade_change : -u_fade_change)`, clamped.
     */
    fun alphaAt(fadeChange: Float): Float =
        (opacity + if (placed) fadeChange else -fadeChange).coerceIn(0f, 1f)

    companion object {
        fun create(
            previous: OpacityState?,
            increment: Float,
            placed: Boolean,
            skipFade: Boolean = false,
        ): OpacityState {
            val opacity = if (previous != null) {
                (previous.opacity + if (previous.placed) increment else -increment).coerceIn(0f, 1f)
            } else {
                if (skipFade && placed) 1f else 0f
            }
            /* The two states nearly every symbol is in are shared: an `OpacityState` is immutable, and
             * a placement records one per symbol of every bucket in the layout ring -- tens of
             * thousands where a style draws every tree -- almost all of them hidden. */
            return when {
                opacity == 0f && !placed -> HIDDEN
                opacity == 1f && placed -> SHOWN
                else -> OpacityState(opacity, placed)
            }
        }

        private val HIDDEN = OpacityState(opacity = 0f, placed = false)
        private val SHOWN = OpacityState(opacity = 1f, placed = true)
    }
}

/** A symbol's icon and label fade together but not necessarily to the same value. */
internal class JointOpacityState(
    val text: OpacityState,
    val icon: OpacityState,
) {
    fun isHidden(): Boolean = text.isHidden() && icon.isHidden()

    companion object {
        fun create(
            previous: JointOpacityState?,
            increment: Float,
            placedText: Boolean,
            placedIcon: Boolean,
            skipFade: Boolean = false,
        ): JointOpacityState {
            val text = OpacityState.create(previous?.text, increment, placedText, skipFade)
            val icon = OpacityState.create(previous?.icon, increment, placedIcon, skipFade)
            // Shared for the reason the halves are; see `OpacityState.create`.
            return when {
                text === HIDDEN.text && icon === HIDDEN.icon -> HIDDEN
                text === SHOWN.text && icon === SHOWN.icon -> SHOWN
                else -> JointOpacityState(text, icon)
            }
        }

        private val HIDDEN = JointOpacityState(
            OpacityState.create(null, 0f, placed = false),
            OpacityState.create(null, 0f, placed = false),
        )
        private val SHOWN = JointOpacityState(
            OpacityState.create(null, 0f, placed = true, skipFade = true),
            OpacityState.create(null, 0f, placed = true, skipFade = true),
        )
    }
}

/**
 * One symbol's placement decision, ported from `JointPlacement` in `src/symbol/placement.ts`.
 *
 * [skipFade] is upstream's own comment: "outside viewport, but within CollisionIndex::viewportPadding
 * px of the edge. Because these symbols aren't onscreen yet, we can skip the 'fade in' animation,
 * and if a subsequent viewport change brings them into view, they'll be fully visible right away."
 */
internal data class JointPlacement(
    val text: Boolean,
    val icon: Boolean,
    val skipFade: Boolean,
) {
    companion object {
        /** A symbol that placed neither half, which is most of them; immutable, so shared. */
        val REJECTED = JointPlacement(text = false, icon = false, skipFade = false)
    }
}
