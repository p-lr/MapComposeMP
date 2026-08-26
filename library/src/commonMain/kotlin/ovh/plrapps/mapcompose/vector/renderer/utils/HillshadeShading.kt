package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.DemData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The hillshade shading maths, ported from maplibre-gl-js.
 *
 * Two shader passes live here, both per-pixel and both pure:
 * - [sobelDeriv] is `src/shaders/hillshade_prepare.fragment.glsl`, which turns a 3x3 neighbourhood
 *   of elevations into a slope vector.
 * - [shadePixel] is `src/shaders/hillshade.fragment.glsl`, which lights that slope.
 *
 * They are kept out of [ovh.plrapps.mapcompose.vector.renderer.HillshadeLayerPainter] so that
 * `commonTest` can assert on them directly, the way `RasterColorMatrixTest` does for
 * [rasterColorMatrix] -- the painter itself needs an `ImageBitmap`, which only `skiaTest` can
 * allocate.
 */

/** The equator's length in metres, upstream's `EXTENT`-independent constant. */
private const val EARTH_CIRCUMFERENCE = 40075016.6855785

/** Upstream's arbitrary z-factor, applied to the slope before it is lit. */
private const val Z_FACTOR = 1.25

/**
 * Ground resolution of one DEM sample, in metres.
 *
 * Upstream folds this into the literal `19.2562` (`log2(8 * EARTH_CIRCUMFERENCE / 512)`), which
 * hardcodes a 512 px DEM tile. Deriving it from [dim] instead makes a 256 px DEM -- which the
 * style spec allows and Terrarium sources commonly serve -- come out right; at `dim = 512` the two
 * agree exactly.
 */
fun metersPerPixel(demZoom: Int, dim: Int): Double =
    EARTH_CIRCUMFERENCE / (dim.toDouble() * 2.0.pow(demZoom.toDouble()))

/**
 * The divisor the Sobel sum is scaled by, `pow(2, exaggeration) * 8 * metersPerPixel`.
 *
 * The `exaggeration` term is upstream's low-zoom boost: below z15 the relief is deliberately
 * amplified, because at those zooms a real slope spans too few pixels to read. [tileZoom] is the
 * zoom of the tile being drawn (upstream's `overscaledZ`), while [demZoom] and [dim] describe the
 * elevation data itself, which differ when the source is overzoomed.
 */
fun slopeDivisor(tileZoom: Double, demZoom: Int, dim: Int): Double {
    val exaggerationFactor = when {
        tileZoom < 2.0 -> 0.4
        tileZoom < 4.5 -> 0.35
        else -> 0.3
    }
    val exaggeration = if (tileZoom < 15.0) (tileZoom - 15.0) * exaggerationFactor else 0.0
    return 2.0.pow(exaggeration) * 8.0 * metersPerPixel(demZoom, dim)
}

/**
 * The slope at one DEM sample, as upstream's prepare pass computes it.
 *
 * A Sobel operator over the 3x3 neighbourhood `a b c / d e f / g h i` -- the centre sample is not
 * read, which is upstream's operator too. The elevations are divided by 4 and the result clamped to
 * `[-1, 1]`, both because upstream packs this into an 8-bit texture as `deriv / 2 + 0.5`; the clamp
 * is load-bearing, it is what caps the shading on a cliff.
 *
 * [x] and [y] may sit on the tile's edge: [DemData] carries a border ring for exactly this.
 */
fun sobelDeriv(dem: DemData, x: Int, y: Int, divisor: Double): Pair<Double, Double> {
    val a = dem[x - 1, y - 1] / 4.0
    val b = dem[x, y - 1] / 4.0
    val c = dem[x + 1, y - 1] / 4.0
    val d = dem[x - 1, y] / 4.0
    val f = dem[x + 1, y] / 4.0
    val g = dem[x - 1, y + 1] / 4.0
    val h = dem[x, y + 1] / 4.0
    val i = dem[x + 1, y + 1] / 4.0

    val derivX = ((c + f + f + i) - (a + d + d + g)) / divisor
    val derivY = ((g + h + h + i) - (a + b + b + c)) / divisor
    return derivX.coerceIn(-1.0, 1.0) to derivY.coerceIn(-1.0, 1.0)
}

/**
 * The latitudes of a tile's top and bottom edges, in degrees.
 *
 * A port of `getTileLatRange` in `src/render/draw_hillshade.ts`; the shading needs it to undo
 * Mercator's north-south stretch, which otherwise makes high-latitude terrain look far steeper than
 * it is (mapbox-gl-js #4807).
 */
fun tileLatRange(z: Int, y: Int): Pair<Double, Double> {
    val tiles = 2.0.pow(z.toDouble())
    return mercatorYToLatitude(y / tiles) to mercatorYToLatitude((y + 1) / tiles)
}

/** Inverse Web Mercator: a normalized y in `[0, 1]` to a latitude in degrees. */
fun mercatorYToLatitude(y: Double): Double =
    atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

/**
 * Lights one slope sample, as upstream's render pass does.
 *
 * [intensity] is `hillshade-exaggeration` -- upstream names the uniform `u_light.x` and uses it both
 * as an opacity and as the exponent base that reshapes the slope, which is why the property is
 * documented as "intensity of the hillshade" rather than as a height multiplier. [azimuthRad] is
 * `hillshade-illumination-direction` in radians, *without* upstream's `+ PI`; that is applied here.
 *
 * MapLibre's colour uniforms are premultiplied and the layer blends with
 * `ONE, ONE_MINUS_SRC_ALPHA`, so the composite below is in premultiplied space. The result is
 * un-premultiplied on the way out, because Compose composites unpremultiplied with `SrcOver`.
 */
fun shadePixel(
    derivX: Double,
    derivY: Double,
    latitude: Double,
    intensity: Double,
    azimuthRad: Double,
    shadow: Color,
    highlight: Color,
    accent: Color,
): Color {
    val scaleFactor = cos(latitude * PI / 180.0)
    val slope = atan(Z_FACTOR * sqrt(derivX * derivX + derivY * derivY) / scaleFactor)
    val aspect = if (derivX != 0.0) {
        atan2(derivY, -derivX)
    } else {
        if (derivY > 0.0) PI / 2.0 else -PI / 2.0
    }

    /* + PI so that the property matches the global light object, whose 0 degrees is north. */
    val azimuth = azimuthRad + PI

    /* Reshapes the slope the way the style spec's exponential interpolation does, so that a higher
     * intensity makes the shading more opaque rather than merely brighter. */
    val base = 1.875 - intensity * 1.75
    val maxValue = 0.5 * PI
    val scaledSlope = if (intensity != 0.5) {
        ((base.pow(slope) - 1.0) / (base.pow(maxValue) - 1.0)) * maxValue
    } else {
        slope
    }

    /* Clamped so that an intensity at or above 0.5 does not additionally scale the colours, while a
     * lower one makes the whole layer more transparent. */
    val clampedIntensity = (intensity * 2.0).coerceIn(0.0, 1.0)

    /* The accent eases in with cos and the shade eases out with sin, so the two never peak at the
     * same slope. */
    val accentWeight = (1.0 - cos(scaledSlope)) * clampedIntensity
    val shade = abs((((aspect + azimuth) / PI + 0.5) fmod 2.0) - 1.0)
    val shadeWeight = sin(scaledSlope) * clampedIntensity

    val accentPre = accent.premultipliedTimes(accentWeight)
    val shadePre = mixPremultiplied(shadow, highlight, shade).times(shadeWeight)

    val out = accentPre.times(1.0 - shadePre.a).plus(shadePre)
    return out.unpremultiply()
}

/** GLSL `mod`, which unlike Kotlin's `%` never returns a negative value for a positive divisor. */
private infix fun Double.fmod(other: Double): Double {
    val r = this % other
    return if (r < 0.0) r + other else r
}

/** A premultiplied RGBA quadruple, the space upstream's shader arithmetic happens in. */
private data class Premultiplied(val r: Double, val g: Double, val b: Double, val a: Double) {
    fun times(k: Double) = Premultiplied(r * k, g * k, b * k, a * k)
    fun plus(other: Premultiplied) =
        Premultiplied(r + other.r, g + other.g, b + other.b, a + other.a)

    fun unpremultiply(): Color {
        val alpha = a.coerceIn(0.0, 1.0)
        if (alpha <= 0.0) return Color.Transparent
        return Color(
            red = (r / alpha).coerceIn(0.0, 1.0).toFloat(),
            green = (g / alpha).coerceIn(0.0, 1.0).toFloat(),
            blue = (b / alpha).coerceIn(0.0, 1.0).toFloat(),
            alpha = alpha.toFloat(),
        )
    }
}

private fun Color.premultipliedTimes(k: Double) = Premultiplied(
    r = red.toDouble() * alpha * k,
    g = green.toDouble() * alpha * k,
    b = blue.toDouble() * alpha * k,
    a = alpha.toDouble() * k,
)

/** GLSL `mix` between two premultiplied colours. */
private fun mixPremultiplied(from: Color, to: Color, t: Double): Premultiplied {
    val a = from.premultipliedTimes(1.0)
    val b = to.premultipliedTimes(1.0)
    return Premultiplied(
        r = a.r + (b.r - a.r) * t,
        g = a.g + (b.g - a.g) * t,
        b = a.b + (b.b - a.b) * t,
        a = a.a + (b.a - a.a) * t,
    )
}
