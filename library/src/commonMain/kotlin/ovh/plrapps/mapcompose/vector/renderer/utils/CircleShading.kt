package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * The `circle` layer's radial profile, ported from maplibre-gl-js.
 *
 * Upstream draws one quad per circle, extending to `R = circle-radius + circle-stroke-width`, and
 * shades it in `src/shaders/glsl/circle.fragment.glsl` from `extrude_length`, which is the fragment's
 * distance from the centre divided by `R`. Two `smoothstep`s do all the work:
 *
 * ```glsl
 * // circle.vertex.glsl
 * float antialiasblur = -max(1.0 / u_device_pixel_ratio / (radius + stroke_width), blur);
 * // circle.fragment.glsl
 * float opacity_t = smoothstep(0.0, antialiased_blur, extrude_length - 1.0);
 * float color_t = stroke_width < 0.01 ? 0.0 : smoothstep(antialiased_blur, 0.0, extrude_length - radius / (radius + stroke_width));
 * fragColor = v_visibility * opacity_t * mix(color * opacity, stroke_color * stroke_opacity, color_t);
 * ```
 *
 * So one band width `B` -- [circleAntialiasBlur], upstream's `antialiasblur` with its sign flipped --
 * governs both: [circleCoverage] fades the whole disc's alpha out over the last `B` of `R`, and
 * [circleStrokeMix] cross-fades the fill colour into the stroke colour over the `B` **inside** the
 * fill radius. `circle-blur` is therefore coverage over the *combined* radius, not a fade of the
 * fill alone, and a blurred circle's stroke fades with it.
 *
 * Everything here is a pure function of `t = distance / R` so that `commonTest` can assert on it, the
 * way [HillshadeShading]'s and [HeatmapKernel]'s are; the painter itself needs an `ImageBitmap`,
 * which only `skiaTest` can allocate. [circleGradientStops] samples the profile into the colour stops
 * of a radial gradient, which is how a fragment shader is reached from Compose.
 */

/** Upstream's `stroke_width < 0.01` test, in style pixels -- below it there is no stroke colour. */
const val CIRCLE_MIN_STROKE_WIDTH = 0.01f

/**
 * Interior samples emitted per interval between two breakpoints of the profile.
 *
 * Skia interpolates between two stops linearly, so this is what bounds the error against the
 * shader's cubic ramp. It is a count per interval rather than a spacing because a band's width is a
 * fraction of the radius, so the error is scale-invariant: at 10 the worst case is under 0.005 of
 * alpha, which is a little over one 8-bit step.
 */
private const val SAMPLES_PER_INTERVAL = 10

/** Two breakpoints closer than this are the same breakpoint. */
private const val BREAKPOINT_EPSILON = 1e-4f

/**
 * The width of the feathered band, as a fraction of [totalRadius], for a `circle-blur` of [blur].
 *
 * Upstream's `antialiasblur`, negated: the shader keeps it negative only so that the two
 * `smoothstep`s can run in opposite directions. Its floor is the faux-antialiasing of one **device**
 * pixel -- and [totalRadius] here is already in the tile bitmap's pixels, which is what the shader's
 * `radius / u_device_pixel_ratio` is in, so no density term belongs in it.
 */
fun circleAntialiasBlur(totalRadius: Float, blur: Float): Float =
    max(if (totalRadius > 0f) 1f / totalRadius else 1f, blur)

/** `opacity_t`: the disc's coverage at `t = distance / totalRadius`. */
fun circleCoverage(t: Float, aaBlur: Float): Float = smoothstep(0f, -aaBlur, t - 1f)

/**
 * `color_t`: how much of the stroke colour shows at `t = distance / totalRadius`.
 *
 * [radiusRatio] is `circle-radius / totalRadius`, so the ramp ends exactly at the fill's edge and
 * starts `aaBlur` inside it. With a `circle-radius` of 0 that puts the whole disc in the stroke
 * colour, which is what upstream draws for a stroke-only circle.
 */
fun circleStrokeMix(t: Float, radiusRatio: Float, aaBlur: Float, hasStroke: Boolean): Float =
    if (!hasStroke) 0f else smoothstep(-aaBlur, 0f, t - radiusRatio)

/**
 * The profile sampled as the colour stops of a radial gradient of radius `radius + strokeWidth`.
 *
 * [fill] and [stroke] carry `circle-opacity` and `circle-stroke-opacity` already, as the shader's
 * `color * opacity` and `stroke_color * stroke_opacity` do.
 *
 * Sampling is adaptive rather than uniform because either band can be as narrow as `1 / R`: the
 * profile's breakpoints are taken first, then a fixed number of samples fills each interval between
 * them. Nothing here depends on a circle's centre, so a feature's hundreds of vertices share one set
 * of stops and only the `Brush` is per-vertex.
 */
fun circleGradientStops(
    radius: Float,
    strokeWidth: Float,
    blur: Float,
    fill: Color,
    stroke: Color,
    hasStroke: Boolean,
): Array<Pair<Float, Color>> {
    val totalRadius = radius + strokeWidth
    val aaBlur = circleAntialiasBlur(totalRadius, blur)
    val radiusRatio = if (totalRadius > 0f) radius / totalRadius else 0f

    val breakpoints = mutableListOf(0f, 1f - aaBlur, 1f)
    if (hasStroke) {
        breakpoints += radiusRatio - aaBlur
        breakpoints += radiusRatio
    }
    val bounds = breakpoints.map { it.coerceIn(0f, 1f) }.sorted().fold(mutableListOf<Float>()) { acc, t ->
        if (acc.isEmpty() || t - acc.last() > BREAKPOINT_EPSILON) acc += t
        acc
    }

    val stops = ArrayList<Pair<Float, Color>>(bounds.size * SAMPLES_PER_INTERVAL + 1)
    for (i in 0 until bounds.size - 1) {
        val from = bounds[i]
        val span = bounds[i + 1] - from
        for (k in 0 until SAMPLES_PER_INTERVAL) {
            val t = from + span * k / SAMPLES_PER_INTERVAL
            stops += t to circleColorAt(t, radiusRatio, aaBlur, hasStroke, fill, stroke)
        }
    }
    stops += 1f to circleColorAt(1f, radiusRatio, aaBlur, hasStroke, fill, stroke)
    return stops.toTypedArray()
}

/**
 * One sample of `opacity_t * mix(color * opacity, stroke_color * stroke_opacity, color_t)`.
 *
 * The `mix` is done in **premultiplied** space, as it is in the shader -- upstream's `Color` stores
 * `r`, `g`, `b` already multiplied by alpha ("Defined in sRGB color space and pre-blended with
 * alpha", `expression/types/color.ts`) -- and the result is then unpremultiplied back to a Compose
 * [Color]. That last step is not just bookkeeping: Skia interpolates gradient stops
 * *unpremultiplied*, so returning `Color.Transparent` for the outermost stop would drag every
 * coloured circle's edge towards black. Carrying the hue and putting the fade in the alpha alone is
 * what makes the interpolation between two neighbouring samples reproduce the shader.
 */
private fun circleColorAt(
    t: Float,
    radiusRatio: Float,
    aaBlur: Float,
    hasStroke: Boolean,
    fill: Color,
    stroke: Color,
): Color {
    val colorT = circleStrokeMix(t, radiusRatio, aaBlur, hasStroke)
    val alpha = mix(fill.alpha, stroke.alpha, colorT)
    val base = if (alpha > 0f) {
        Color(
            red = (mix(fill.red * fill.alpha, stroke.red * stroke.alpha, colorT) / alpha).coerceIn(0f, 1f),
            green = (mix(fill.green * fill.alpha, stroke.green * stroke.alpha, colorT) / alpha).coerceIn(0f, 1f),
            blue = (mix(fill.blue * fill.alpha, stroke.blue * stroke.alpha, colorT) / alpha).coerceIn(0f, 1f),
        )
    } else {
        /* Both colours are fully transparent there, so the hue is arbitrary -- but it still has to be
         * a hue and not black, for the reason above. */
        if (colorT < 0.5f) fill else stroke
    }
    return base.copy(alpha = (alpha * circleCoverage(t, aaBlur)).coerceIn(0f, 1f))
}

/** GLSL's `mix`. */
private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
