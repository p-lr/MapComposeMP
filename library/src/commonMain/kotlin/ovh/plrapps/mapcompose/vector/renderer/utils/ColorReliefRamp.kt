package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.elevationInterpolate
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsElevationColor

/**
 * A `color-relief-color` compiled into the ramp the fragment shader samples.
 *
 * [elevationStops] is ascending and the same length as [colorStops]; [colorAt] is the shader's
 * lookup. Built by [colorReliefRamp], which is where the shape of a valid ramp is decided.
 */
class ColorReliefRamp(
    val elevationStops: List<Double>,
    val colorStops: List<Color>,
) {

    /**
     * The colour at [elevation] metres.
     *
     * A port of `color_relief.fragment.glsl`'s `main`: a binary search for the bracketing pair of
     * stops, then the blend GL's `LINEAR` filter performs between the two texels. Below the first
     * stop and above the last the shader's `x` leaves `0..1` and `CLAMP_TO_EDGE` pins it to the
     * end texel, which is the clamp below.
     *
     * The blend is done in **premultiplied** space, because that is what the colour texture holds:
     * `RGBAImage.setPixel` divides upstream's premultiplied `Color` by its alpha and `Texture`
     * multiplies it straight back in (`premultiply` defaults to true for a `gl.RGBA` texture). The
     * result is unpremultiplied on the way out, since Compose composites unpremultiplied -- the same
     * round trip [circleGradientStops] and [shadePixel] make.
     */
    fun colorAt(elevation: Double): Color {
        val last = elevationStops.size - 1
        if (elevation <= elevationStops[0]) return colorStops[0]
        if (elevation >= elevationStops[last]) return colorStops[last]

        var l = 0
        var r = last
        while (r - l > 1) {
            val m = (r + l) / 2
            if (elevation < elevationStops[m]) r = m else l = m
        }

        val lower = elevationStops[l]
        val upper = elevationStops[l + 1]
        /* Two stops at the same elevation are a step in the ramp; the shader's division by zero
         * would give it an undefined colour, so the lower one wins. */
        if (upper <= lower) return colorStops[l]

        val t = (elevation - lower) / (upper - lower)
        return mixPremultiplied(colorStops[l], colorStops[l + 1], t)
    }
}

/**
 * Builds the ramp a `color-relief` layer draws, or `null` when it would draw nothing.
 *
 * A port of `ColorReliefStyleLayer._createColorRamp`
 * (`src/style/style_layer/color_relief_style_layer.ts`). Upstream reads the stop labels off the
 * *top-level* `interpolate` of `color-relief-color` and evaluates the expression at each of them,
 * so **only** an `interpolate` over `["elevation"]` produces a ramp: a `step`, a `case` or a plain
 * colour string leaves `elevationStops` empty, upstream pads it to one transparent stop, and the
 * layer is invisible. That is reproduced here as a `null` result, which lets the painter return
 * before it allocates a bitmap.
 *
 * Upstream's `maxLength` remapping is deliberately not ported: it exists to fit the ramp into a
 * `MAX_TEXTURE_SIZE`-wide texture, and there is no texture here, so a ramp with more stops than a
 * GPU could upload renders in full rather than decimated.
 */
fun colorReliefRamp(color: ExpressionOrValue<Color>?): ColorReliefRamp? {
    val labels = color.elevationInterpolate()?.labels ?: return null
    if (labels.isEmpty()) return null

    val colors = labels.map { color.processAsElevationColor(it) ?: return null }

    /* Upstream's second padding rule -- a one-stop ramp is widened by a metre so the shader's
     * `(el - el_l) / (el_r - el_l)` has a span to divide by. Its first rule, the transparent
     * placeholder for an empty ramp, is the `null` above. */
    if (labels.size < 2) {
        return ColorReliefRamp(
            elevationStops = listOf(labels[0], labels[0] + 1.0),
            colorStops = listOf(colors[0], colors[0]),
        )
    }
    return ColorReliefRamp(elevationStops = labels, colorStops = colors)
}

/**
 * GL's `LINEAR` blend between two texels of the colour ramp, premultiplied in and unpremultiplied
 * out. See [ColorReliefRamp.colorAt].
 */
private fun mixPremultiplied(from: Color, to: Color, t: Double): Color {
    val alpha = from.alpha + (to.alpha - from.alpha) * t.toFloat()
    if (alpha <= 0f) return Color.Transparent

    fun channel(a: Float, b: Float): Float {
        val premultiplied = a * from.alpha + (b * to.alpha - a * from.alpha) * t.toFloat()
        return (premultiplied / alpha).coerceIn(0f, 1f)
    }

    return Color(
        red = channel(from.red, to.red),
        green = channel(from.green, to.green),
        blue = channel(from.blue, to.blue),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
