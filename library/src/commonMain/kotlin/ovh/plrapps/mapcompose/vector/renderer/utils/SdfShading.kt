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
 */
fun sdfHaloBuffer(haloWidth: Float, fontScale: Float): Float {
    if (fontScale <= 0f) return SDF_FILL_BUFFER
    return ((6f - haloWidth / fontScale) / SDF_PX).coerceIn(0f, 1f)
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
