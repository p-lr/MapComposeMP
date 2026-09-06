package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import ovh.plrapps.mapcompose.ui.layout.grid
import ovh.plrapps.mapcompose.ui.state.ZoomPanRotateState
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.symbol.SymbolInstance
import ovh.plrapps.mapcompose.vector.symbol.fractionalZoom
import ovh.plrapps.mapcompose.vector.ui.state.SymbolState
import kotlin.math.ceil

@Composable
internal fun SymbolComposer(
    modifier: Modifier,
    zoomPRState: ZoomPanRotateState,
    symbolState: SymbolState
) {
    val placement = symbolState.placement
    val density = LocalDensity.current.density

    /* The fade clock, which is upstream's `u_fade_change` uniform: how far every fade has advanced
     * since the placement committed. A placement is *held* (see `Placement.stillRecent`), so the
     * fade has to keep running between commits -- upstream's vertex shader adds the same term to the
     * committed opacity.
     *
     * It is created **with** the placement, by `remember(placement)`, and that is the whole point:
     * the clock belongs to one placement cycle and no frame may ever read another cycle's. It used
     * to be a single `elapsedMillis` state zeroed by the effect body, and an effect body runs when
     * its coroutine is dispatched, which can be after the frame that first draws the new placement.
     * That frame then combined a freshly committed opacity with the *previous* cycle's fade change,
     * which is close to a full fade by the time a new placement commits -- and the arithmetic of
     * [OpacityState.alphaAt] makes exactly one class of symbol flash:
     *
     * - an already-visible symbol is `1 + fadeChange`, clamped back to 1: nothing happens;
     * - a symbol fading out is `1 - fadeChange`: it blinks to nothing for one frame;
     * - a **newly placed** symbol is `0 + fadeChange`: it flashes in at nearly full opacity, is then
     *   drawn at 0 as soon as the effect runs, and only then fades in over its 300 ms.
     *
     * Which is precisely "labels flicker when they appear, ones already on screen do not".
     * `remember` runs during composition, so the clock is at zero before anything can draw. */
    val fade = remember(placement) { FadeClock() }
    LaunchedEffect(fade) {
        if (placement.fadeRemainingMs <= 0L) return@LaunchedEffect
        var start = -1L
        while (fade.elapsedMillis < placement.fadeRemainingMs) {
            withFrameMillis { frameTime ->
                if (start < 0L) start = frameTime
                fade.elapsedMillis = frameTime - start
            }
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val x0 = ((ceil(zoomPRState.scrollX / grid) * grid)).toInt()
        val y0 = ((ceil(zoomPRState.scrollY / grid) * grid)).toInt()
        val fadeChange = placement.fadeChangeAt(fade.elapsedMillis)
        val zoom = fractionalZoom(zoomPRState.fullWidth, zoomPRState.scale, density)
        val curveEpsilonPx = CurvedLabel.CURVE_EPSILON_DP * density

        withTransform({
            rotate(
                degrees = zoomPRState.rotation,
                pivot = Offset(
                    x = zoomPRState.pivotX.toFloat(),
                    y = zoomPRState.pivotY.toFloat()
                )
            )
            translate(
                left = (-zoomPRState.scrollX + x0).toFloat(),
                top = (-zoomPRState.scrollY + y0).toFloat()
            )
        }) {
            for (phase in symbolState.visiblePhases) {
                val phaseOffsetPx = phase * zoomPRState.fullWidth * zoomPRState.scale
                /* A view, not a copy: this runs once per phase on every frame. */
                for (placed in placement.symbols.asReversed()) {
                    val symbol = placed.instance
                    val iconAlpha = placed.opacity.icon.alphaAt(fadeChange)
                    val textAlpha = placed.opacity.text.alphaAt(fadeChange)
                    if (iconAlpha <= 0f && textAlpha <= 0f) continue

                    /* Recomputed every frame, not read off the placement: a held placement still has
                     * to grow and shrink with the map, which is what upstream's per-frame `u_size` /
                     * `u_size_t` uniforms do while its collision boxes keep the placement's zoom. */
                    val iconScale = placed.iconScaleAt(zoom)
                    val textScale = placed.textScaleAt(zoom)

                    val canvasX: Float
                    val canvasY: Float
                    if (symbol is SymbolInstance.Text && symbol.spriteAnchorGlobal != null && symbol.textOffset != null) {
                        canvasX = (symbol.spriteAnchorGlobal.x * zoomPRState.fullWidth * zoomPRState.scale - x0 +
                                   symbol.textOffset.x * textScale + phaseOffsetPx).toFloat()
                        canvasY = (symbol.spriteAnchorGlobal.y * zoomPRState.fullHeight * zoomPRState.scale - y0 +
                                   symbol.textOffset.y * textScale).toFloat()
                    } else {
                        canvasX = (symbol.global.x * zoomPRState.fullWidth * zoomPRState.scale - x0 + phaseOffsetPx).toFloat()
                        canvasY = (symbol.global.y * zoomPRState.fullHeight * zoomPRState.scale - y0).toFloat()
                    }

                    /* A label that follows a line is drawn along the *projected* line, not as one
                     * rigid bitmap at the anchor segment's angle -- upstream's `updateLineLabels`,
                     * per frame, for the same reason the scale above is per frame. */
                    if (symbol is SymbolInstance.Text && symbol.globalLine != null) {
                        val drawn = drawLineLabel(
                            symbol = symbol,
                            textScale = textScale,
                            textAlpha = textAlpha,
                            curveEpsilonPx = curveEpsilonPx,
                            mapRotationDeg = zoomPRState.rotation,
                            canvasX = canvasX,
                            canvasY = canvasY,
                        ) { point ->
                            Offset(
                                (point.x * zoomPRState.fullWidth * zoomPRState.scale - x0 + phaseOffsetPx).toFloat(),
                                (point.y * zoomPRState.fullHeight * zoomPRState.scale - y0).toFloat(),
                            )
                        }
                        if (drawn) continue
                    }

                    val size = symbol.scaledSize(iconScale, textScale)
                    val align = symbol.scaledAlign(iconScale, textScale)
                    val offsetX = size.width * align.x
                    val offsetY = size.height * align.y

                    withTransform({
                        translate(left = canvasX + offsetX, top = canvasY + offsetY)
                        if (symbol.viewportAligned) {
                            // Counter-rotate around the symbol's geographic reference point in local
                            // coords (= -offsetX, -offsetY), so the symbol stays horizontal on screen
                            // while its position still tracks the map-rotated viewport coordinate.
                            rotate(degrees = -zoomPRState.rotation, pivot = Offset(-offsetX, -offsetY))
                        }
                    }) {
                        symbol.drawScaled(
                            drawScope = this,
                            iconAlpha = iconAlpha,
                            textAlpha = textAlpha,
                            iconScale = iconScale,
                            textScale = textScale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws a line label along its own line, and says whether it drew anything.
 *
 * Returns false when the label's line does not resolve -- the anchor is not on it, or the label is
 * wider than what is left of it -- and the caller falls back to the ordinary rigid draw.
 *
 * The two paths differ only in how far the line bends: past [curveEpsilonPx] the label is drawn
 * glyph by glyph, below it as one blit at the path's chord angle. Both use the chord, so a label
 * does not jump as the map zooms across the threshold.
 */
private fun DrawScope.drawLineLabel(
    symbol: SymbolInstance.Text,
    textScale: Float,
    textAlpha: Float,
    curveEpsilonPx: Float,
    mapRotationDeg: Float,
    canvasX: Float,
    canvasY: Float,
    project: (Point) -> Offset,
): Boolean {
    if (textAlpha <= 0f) return true
    val path = CurvedLabel.pathFor(symbol, project) ?: return false
    val art = symbol.value

    if (art is LabelArt.Glyphs) {
        val placements = CurvedLabel.place(
            art = art,
            path = path,
            textScale = textScale,
            alongOffset = symbol.lineOffsetX,
            perpendicular = symbol.lineOffsetY,
            keepUpright = symbol.keepUpright,
            curveEpsilonPx = curveEpsilonPx,
            mapRotationDeg = mapRotationDeg,
        )
        if (placements != null) {
            drawTextAlongPath(art, placements, alpha = textAlpha, scale = textScale)
            return true
        }
    }

    val chord = CurvedLabel.chordAngleDeg(path)
    /* `text-keep-upright` on the straight path, decided the same way the glyph path decides it:
     * in screen space, bearing included, so a chord running right to left is turned around. */
    val backwards = symbol.keepUpright &&
        CurvedLabel.readsBackwards(path.points.first(), path.points.last(), mapRotationDeg)
    val angle = if (backwards) chord + 180f else chord
    translate(canvasX, canvasY) {
        translate(-art.width * textScale / 2f, -art.height * textScale / 2f) {
            drawTextAtAngle(art, angle, alpha = textAlpha, scale = textScale)
        }
    }
    return true
}

/**
 * How far the current placement's fades have advanced, in milliseconds since it was first drawn.
 *
 * One instance per [ovh.plrapps.mapcompose.vector.symbol.PlacementResult]; see the comment at its
 * `remember` in [SymbolComposer] for why that matters.
 */
private class FadeClock {
    var elapsedMillis by mutableLongStateOf(0L)
}
