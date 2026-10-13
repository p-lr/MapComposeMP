package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color

/**
 * The signed-distance-field shading maths, ported from maplibre-gl-js
 * `src/shaders/symbol_sdf.fragment.glsl`.
 *
 * An SDF sprite stores, in its alpha channel, a distance to the shape's edge rather than the shape
 * itself: `1` deep inside, `0` far outside, and a linear ramp across the boundary. Recolouring one
 * is therefore two `smoothstep`s over that channel -- one at the fill's edge, one further out at the
 * halo's -- which is exactly what the fragment shader does per pixel and what [sdfPixel] does here.
 *
 * The two are pure functions so `commonTest` can cover them, leaving only the pixel loop in
 * [ovh.plrapps.mapcompose.vector.data.SpriteManager] -- the same split as
 * [ovh.plrapps.mapcompose.vector.renderer.utils.shadePixel] for hillshade.
 *
 * **Divergence:** upstream evaluates this in the fragment shader, at the screen resolution the icon
 * is finally drawn at. Here it runs once when the sprite is cut out of the sheet, so the result is
 * resampled when the icon is scaled -- the same trade the tile painters make. [fontScale] is what
 * keeps the halo the right width regardless: it is the ratio between the size the icon is drawn at
 * and its size on the sheet, so a halo specified in layout pixels is converted into the distance
 * field's own units before the thresholds are picked.
 */

/** Sheet pixels per unit of the distance field, upstream's `SDF_PX`. */
const val SDF_PX = 8f

/** Upstream's `EDGE_GAMMA`: the width of the antialiased ramp, in distance-field units. */
const val SDF_EDGE_GAMMA = 0.105f

/** Upstream's `blurOffset`, which turns a `*-halo-blur` in pixels into extra ramp width. */
private const val SDF_BLUR_OFFSET = 1.19f

/**
 * The distance at which the shape's own edge sits: upstream's `(256 - 64) / 256`.
 *
 * The atlas encodes 64 of its 256 alpha levels as "outside", so the boundary is not at the middle
 * of the range.
 */
const val SDF_FILL_BUFFER = (256f - 64f) / 256f

/**
 * The distance at which a halo of [haloWidth] layout pixels ends.
 *
 * Upstream: `(6.0 - u_halo_width / fontScale) / SDF_PX`. A wider halo means a *lower* threshold,
 * because a lower distance is further outside the shape. A zero halo lands on [SDF_FILL_BUFFER], so
 * the halo pass contributes nothing -- which is why no special case is needed for it.
 *
 * The result is deliberately **not** clamped at zero, as upstream's is not. A distance field only
 * carries `GLYPH_BORDER` units of outside distance, so a halo wider than that is already clipped by
 * the field; clamping the threshold to zero on top of that turned every sample in the border ring
 * into full-strength halo with a hard edge, rather than letting the smoothstep run off the end of
 * the field and taper.
 */
fun sdfHaloBuffer(haloWidth: Float, fontScale: Float): Float {
    if (fontScale <= 0f) return SDF_FILL_BUFFER
    return (6f - haloWidth / fontScale) / SDF_PX
}

/** Half the width of the antialiased ramp, widened by `*-halo-blur` on the halo pass. */
fun sdfGamma(haloBlur: Float, fontScale: Float, isHalo: Boolean): Float {
    if (fontScale <= 0f) return SDF_EDGE_GAMMA
    val blur = if (isHalo) haloBlur * SDF_BLUR_OFFSET / SDF_PX / fontScale else 0f
    return blur + SDF_EDGE_GAMMA / fontScale
}

/** GLSL's `smoothstep`. */
fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge0 == edge1) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * One recoloured pixel of an SDF sprite.
 *
 * [distance] is the alpha the sheet stores at that pixel. The fill is composited *over* the halo,
 * which is what keeps a translucent fill from being brightened by the halo showing through it --
 * summing the two, as an additive pass would, turns the boundary into a bright rim.
 */
fun sdfPixel(
    distance: Float,
    fillColor: Color,
    haloColor: Color,
    haloWidth: Float,
    haloBlur: Float,
    fontScale: Float,
): Color {
    val fillGamma = sdfGamma(haloBlur, fontScale, isHalo = false)
    val haloGamma = sdfGamma(haloBlur, fontScale, isHalo = true)
    val haloBuffer = sdfHaloBuffer(haloWidth, fontScale)

    val fillAlpha = smoothstep(SDF_FILL_BUFFER - fillGamma, SDF_FILL_BUFFER + fillGamma, distance) *
        fillColor.alpha
    val haloAlpha = smoothstep(haloBuffer - haloGamma, haloBuffer + haloGamma, distance) *
        haloColor.alpha

    val behind = haloAlpha * (1f - fillAlpha)
    val outAlpha = fillAlpha + behind
    if (outAlpha <= 0f) return Color.Transparent

    return Color(
        red = (fillColor.red * fillAlpha + haloColor.red * behind) / outAlpha,
        green = (fillColor.green * fillAlpha + haloColor.green * behind) / outAlpha,
        blue = (fillColor.blue * fillAlpha + haloColor.blue * behind) / outAlpha,
        alpha = outAlpha,
    )
}

/**
 * [sdfPixel] with its per-glyph arguments fixed, returning the packed straight-alpha ARGB that
 * `sdfPixel(...).toArgb()` returns -- bit for bit, by the very quantization an sRGB `Color` applies
 * to each channel -- without building a `Color` per pixel.
 *
 * Label rasterization runs this for every pixel of every glyph, and the two `Color`s per pixel the
 * plain call costs (each range-checked and quantized, then unpacked again by [sourceOverArgb]'s
 * caller) were most of what the symbol layout pass spent its time on.
 */
internal class SdfShade(
    fillColor: Color,
    haloColor: Color,
    haloWidth: Float,
    haloBlur: Float,
    fontScale: Float,
) {
    private val fillLow: Float
    private val fillHigh: Float
    private val haloLow: Float
    private val haloHigh: Float

    init {
        val fillGamma = sdfGamma(haloBlur, fontScale, isHalo = false)
        val haloGamma = sdfGamma(haloBlur, fontScale, isHalo = true)
        val haloBuffer = sdfHaloBuffer(haloWidth, fontScale)
        fillLow = SDF_FILL_BUFFER - fillGamma
        fillHigh = SDF_FILL_BUFFER + fillGamma
        haloLow = haloBuffer - haloGamma
        haloHigh = haloBuffer + haloGamma
    }

    private val fillAlpha = fillColor.alpha
    private val fillRed = fillColor.red
    private val fillGreen = fillColor.green
    private val fillBlue = fillColor.blue
    private val haloAlpha = haloColor.alpha
    private val haloRed = haloColor.red
    private val haloGreen = haloColor.green
    private val haloBlue = haloColor.blue

    /** The shaded pixel at [distance]; its alpha byte is 0 where `sdfPixel` is transparent. */
    fun argb(distance: Float): Int {
        val fill = smoothstep(fillLow, fillHigh, distance) * fillAlpha
        val halo = smoothstep(haloLow, haloHigh, distance) * haloAlpha
        val behind = halo * (1f - fill)
        val outAlpha = fill + behind
        if (outAlpha <= 0f) return 0
        return packArgb(
            alpha = outAlpha,
            red = (fillRed * fill + haloRed * behind) / outAlpha,
            green = (fillGreen * fill + haloGreen * behind) / outAlpha,
            blue = (fillBlue * fill + haloBlue * behind) / outAlpha,
        )
    }
}

/**
 * Source-over of one straight-alpha ARGB pixel onto another, rounding each channel the way an sRGB
 * `Color` does -- the same result as building `Color`s for both and compositing those.
 */
internal fun sourceOverArgb(source: Int, destination: Int): Int {
    val sourceAlpha8 = source ushr 24
    /* Onto nothing, the source comes out as it went in: `s * a / a` rounds back to the same byte,
     * since each channel sits half a step from any rounding boundary. The common case by far -- a
     * glyph's pixels mostly land where no neighbour reached. */
    if (sourceAlpha8 == 255) return source
    if (destination ushr 24 == 0) return if (sourceAlpha8 == 0) 0 else source
    val sourceAlpha = sourceAlpha8 / 255.0f
    val behind = (destination ushr 24) / 255.0f * (1f - sourceAlpha)
    val alpha = sourceAlpha + behind
    if (alpha <= 0f) return 0
    return packArgb(
        alpha = alpha,
        red = (channel(source, 16) * sourceAlpha + channel(destination, 16) * behind) / alpha,
        green = (channel(source, 8) * sourceAlpha + channel(destination, 8) * behind) / alpha,
        blue = (channel(source, 0) * sourceAlpha + channel(destination, 0) * behind) / alpha,
    )
}

private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255.0f

/** `Color(red, green, blue, alpha).toArgb()` for sRGB: each channel rounded to its nearest byte. */
private fun packArgb(alpha: Float, red: Float, green: Float, blue: Float): Int =
    ((alpha * 255.0f + 0.5f).toInt() shl 24) or
        ((red * 255.0f + 0.5f).toInt() shl 16) or
        ((green * 255.0f + 0.5f).toInt() shl 8) or
        (blue * 255.0f + 0.5f).toInt()
