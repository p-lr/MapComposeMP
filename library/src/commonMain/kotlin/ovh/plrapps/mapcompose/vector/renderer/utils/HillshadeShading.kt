package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_BASIC
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_COMBINED
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_IGOR
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_MULTIDIRECTIONAL
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_STANDARD
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
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
 * - [sobelDeriv] is `src/shaders/glsl/hillshade_prepare.fragment.glsl`, which turns a 3x3
 *   neighbourhood of elevations into a slope vector.
 * - [shadePixel] is `src/shaders/glsl/hillshade.fragment.glsl`, which lights that slope by one of
 *   five algorithms.
 *
 * They are kept out of [ovh.plrapps.mapcompose.vector.renderer.HillshadeLayerPainter] so that
 * `commonTest` can assert on them directly, the way `RasterColorMatrixTest` does for
 * [rasterColorMatrix] -- the painter itself needs an `ImageBitmap`, which only `skiaTest` can
 * allocate.
 */

/** The equator's length in metres, upstream's `EXTENT`-independent constant. */
private const val EARTH_CIRCUMFERENCE = 40075016.6855785

/**
 * Upstream's arbitrary z-factor, applied to the slope before `standard` lights it.
 *
 * It is `0.625` and not the `1.25` this used to carry because maplibre-gl-js#5768 rescaled the
 * prepare pass: it used to divide every elevation by 4 and hardcode a 512 px DEM tile (the literal
 * `19.2562`), and now emits the true gradient. Halving the factor while quadrupling the derivative
 * is a *net doubling* for a 512 px tile, which is what upstream's changelog warns about -- and this
 * port, which already derived the ground resolution from the tile's own size, was half of
 * upstream's shading at every tile size until it followed.
 */
private const val Z_FACTOR = 0.625

/**
 * Ground resolution of one DEM sample, in metres.
 *
 * Upstream spells this `pow(2, 28.2562 - u_zoom) / tileSize`, `28.2562` being
 * `log2(8 * EARTH_CIRCUMFERENCE)`; [dim] here is that `tileSize`.
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
 * read, which is upstream's operator too. The result is clamped to `[-4, 4]` because upstream packs
 * it into an 8-bit texture as `deriv / 8 + 0.5`; the clamp is load-bearing, it is what caps the
 * shading on a cliff.
 *
 * [x] and [y] may sit on the tile's edge: [DemData] carries a border ring for exactly this.
 */
fun sobelDeriv(dem: DemData, x: Int, y: Int, divisor: Double): Pair<Double, Double> {
    val a = dem[x - 1, y - 1]
    val b = dem[x, y - 1]
    val c = dem[x + 1, y - 1]
    val d = dem[x - 1, y]
    val f = dem[x + 1, y]
    val g = dem[x - 1, y + 1]
    val h = dem[x, y + 1]
    val i = dem[x + 1, y + 1]

    val derivX = ((c + f + f + i) - (a + d + d + g)) / divisor
    val derivY = ((g + h + h + i) - (a + b + b + c)) / divisor
    return derivX.coerceIn(-4.0, 4.0) to derivY.coerceIn(-4.0, 4.0)
}

/**
 * The latitudes of a tile's top and bottom edges, in degrees.
 *
 * A port of `getTileLatRange` in `src/webgl/program/hillshade_program.ts`; the shading needs it to
 * undo Mercator's north-south stretch, which otherwise makes high-latitude terrain look far steeper
 * than it is (mapbox-gl-js #4807).
 */
fun tileLatRange(z: Int, y: Int): Pair<Double, Double> {
    val tiles = 2.0.pow(z.toDouble())
    return mercatorYToLatitude(y / tiles) to mercatorYToLatitude((y + 1) / tiles)
}

/** Inverse Web Mercator: a normalized y in `[0, 1]` to a latitude in degrees. */
fun mercatorYToLatitude(y: Double): Double =
    atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI

/** The algorithm `hillshade-method` selects, one per branch of `hillshade.fragment.glsl`'s main. */
enum class HillshadeMethod {
    STANDARD, COMBINED, IGOR, MULTIDIRECTIONAL, BASIC;

    companion object {
        /** Upstream's `switch` has a `default:` arm, so an unknown spelling is `standard`. */
        fun ofOrDefault(value: String?): HillshadeMethod = when (value) {
            HILLSHADE_METHOD_BASIC -> BASIC
            HILLSHADE_METHOD_COMBINED -> COMBINED
            HILLSHADE_METHOD_IGOR -> IGOR
            HILLSHADE_METHOD_MULTIDIRECTIONAL -> MULTIDIRECTIONAL
            HILLSHADE_METHOD_STANDARD -> STANDARD
            else -> STANDARD
        }
    }
}

/** One light: upstream's `u_azimuths[i]`, `u_altitudes[i]`, `u_shadows[i]`, `u_highlights[i]`. */
data class IlluminationSource(
    val azimuthRad: Double,
    val altitudeRad: Double,
    val shadow: Color,
    val highlight: Color,
)

/**
 * Zips the four illumination properties into one light per source.
 *
 * A port of `HillshadeStyleLayer.getIlluminationProperties` (`src/style/style_layer/
 * hillshade_style_layer.ts`): the source count is the longest of the four lists, and every shorter
 * one is padded with **its own last element** rather than with the spec default -- a style that
 * writes three directions and one colour means that colour for all three. A list that is empty
 * because the property is absent or failed to compile falls back to the matching `fallback*`
 * argument, which is that property's spec default.
 */
fun illuminationSources(
    directionsDeg: List<Double>,
    altitudesDeg: List<Double>,
    shadows: List<Color>,
    highlights: List<Color>,
    fallbackDirectionDeg: Double,
    fallbackAltitudeDeg: Double,
    fallbackShadow: Color,
    fallbackHighlight: Color,
): List<IlluminationSource> {
    val directions = directionsDeg.ifEmpty { listOf(fallbackDirectionDeg) }
    val altitudes = altitudesDeg.ifEmpty { listOf(fallbackAltitudeDeg) }
    val shadowColors = shadows.ifEmpty { listOf(fallbackShadow) }
    val highlightColors = highlights.ifEmpty { listOf(fallbackHighlight) }

    val count = maxOf(directions.size, altitudes.size, shadowColors.size, highlightColors.size)
    return List(count) { i ->
        IlluminationSource(
            azimuthRad = directions[minOf(i, directions.size - 1)] * PI / 180.0,
            altitudeRad = altitudes[minOf(i, altitudes.size - 1)] * PI / 180.0,
            shadow = shadowColors[minOf(i, shadowColors.size - 1)],
            highlight = highlightColors[minOf(i, highlightColors.size - 1)],
        )
    }
}

/**
 * Lights one slope sample, as upstream's render pass does.
 *
 * [exaggeration] is `hillshade-exaggeration`; `standard` uses it as an opacity *and* as the exponent
 * base that reshapes the slope, which is why the property is documented as "intensity of the
 * hillshade" rather than as a height multiplier, while the four GDAL-derived methods simply scale
 * the derivative by `exaggeration * 2` as upstream does. Only `standard` reads [accent], and only
 * `basic`, `combined` and `multidirectional` read a source's altitude.
 *
 * The Mercator correction is applied here, at the top, exactly as upstream's `main()` applies it
 * before dispatching -- every method sees the corrected derivative.
 *
 * MapLibre's colour uniforms are premultiplied and the layer blends with
 * `ONE, ONE_MINUS_SRC_ALPHA`, so the composites below are in premultiplied space. The result is
 * un-premultiplied on the way out, because Compose composites unpremultiplied with `SrcOver`.
 */
fun shadePixel(
    derivX: Double,
    derivY: Double,
    latitude: Double,
    exaggeration: Double,
    method: HillshadeMethod,
    sources: List<IlluminationSource>,
    accent: Color,
): Color {
    val scaleFactor = cos(latitude * PI / 180.0)
    val dx = derivX / scaleFactor
    val dy = derivY / scaleFactor

    val out = when (method) {
        HillshadeMethod.STANDARD -> standardHillshade(dx, dy, exaggeration, sources[0], accent)
        HillshadeMethod.IGOR -> igorHillshade(dx, dy, exaggeration, sources[0])
        HillshadeMethod.BASIC -> basicHillshade(dx, dy, exaggeration, sources[0])
        HillshadeMethod.COMBINED -> combinedHillshade(dx, dy, exaggeration, sources[0])
        HillshadeMethod.MULTIDIRECTIONAL -> multidirectionalHillshade(dx, dy, exaggeration, sources)
    }
    return out.unpremultiply()
}

/** `get_aspect`, the direction the slope faces. */
private fun aspect(derivX: Double, derivY: Double): Double =
    if (derivX != 0.0) atan2(derivY, -derivX) else if (derivY > 0.0) PI / 2.0 else -PI / 2.0

/**
 * `standard_hillshade`, MapLibre's legacy algorithm and the only one reading `hillshade-accent-color`.
 *
 * The `+ PI` on the azimuth is what makes the property match the global light object, whose 0
 * degrees is north.
 */
private fun standardHillshade(
    derivX: Double,
    derivY: Double,
    intensity: Double,
    source: IlluminationSource,
    accent: Color,
): Premultiplied {
    val slope = atan(Z_FACTOR * sqrt(derivX * derivX + derivY * derivY))
    val azimuth = source.azimuthRad + PI

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
    val shade = abs((((aspect(derivX, derivY) + azimuth) / PI + 0.5) fmod 2.0) - 1.0)
    val shadeWeight = sin(scaledSlope) * clampedIntensity

    val accentPre = accent.premultipliedTimes(accentWeight)
    val shadePre = mixPremultiplied(source.shadow, source.highlight, shade).times(shadeWeight)

    return accentPre.times(1.0 - shadePre.a).plus(shadePre)
}

/**
 * `igor_hillshade`, after `GDALHillshadeIgorAlg`.
 *
 * GDAL only shades; upstream adds the highlight, so `hillshade-highlight-color: transparent`
 * reproduces GDAL's output exactly.
 */
private fun igorHillshade(
    derivX: Double,
    derivY: Double,
    exaggeration: Double,
    source: IlluminationSource,
): Premultiplied {
    val dx = derivX * exaggeration * 2.0
    val dy = derivY * exaggeration * 2.0
    val azimuth = source.azimuthRad + PI
    val slopeStrength = atan(sqrt(dx * dx + dy * dy)) * 2.0 / PI
    val aspectStrength = 1.0 - abs((((aspect(dx, dy) + azimuth) / PI + 0.5) fmod 2.0) - 1.0)
    return source.shadow.premultipliedTimes(slopeStrength * aspectStrength)
        .plus(source.highlight.premultipliedTimes(slopeStrength * (1.0 - aspectStrength)))
}

/** `basic_hillshade`, after `GDALHillshadeAlg`, for one light source. */
private fun basicHillshade(
    derivX: Double,
    derivY: Double,
    exaggeration: Double,
    source: IlluminationSource,
): Premultiplied = basicShade(
    dx = derivX * exaggeration * 2.0,
    dy = derivY * exaggeration * 2.0,
    source = source,
    negateAzimuth = false,
    weight = 1.0,
)

/**
 * `multidirectional_hillshade`: [basicHillshade] averaged over every source.
 *
 * Upstream negates this one's `cos_az` / `sin_az` and takes the azimuth raw, without the `+ PI`
 * every other method adds -- the two together are the same rotation, so a single-source
 * `multidirectional` and a `basic` agree.
 */
private fun multidirectionalHillshade(
    derivX: Double,
    derivY: Double,
    exaggeration: Double,
    sources: List<IlluminationSource>,
): Premultiplied {
    val dx = derivX * exaggeration * 2.0
    val dy = derivY * exaggeration * 2.0
    var out = Premultiplied(0.0, 0.0, 0.0, 0.0)
    for (source in sources) {
        out = out.plus(
            basicShade(
                dx = dx,
                dy = dy,
                source = source,
                negateAzimuth = true,
                weight = 1.0 / sources.size,
            )
        )
    }
    return out
}

/**
 * The body `basic` and `multidirectional` share: one light's contribution.
 *
 * `cang` is the cosine of the angle between the surface normal and the light, and the output steps
 * at `0.5` -- lit halves ramp into the highlight, shaded halves into the shadow, and exactly `0.5`
 * (which is flat ground under a light at 30 degrees) is fully transparent.
 */
private fun basicShade(
    dx: Double,
    dy: Double,
    source: IlluminationSource,
    negateAzimuth: Boolean,
    weight: Double,
): Premultiplied {
    val azimuth = if (negateAzimuth) source.azimuthRad else source.azimuthRad + PI
    val sign = if (negateAzimuth) -1.0 else 1.0
    val cosAz = sign * cos(azimuth)
    val sinAz = sign * sin(azimuth)
    val cosAlt = cos(source.altitudeRad)
    val sinAlt = sin(source.altitudeRad)

    val cang = (sinAlt - (dy * cosAz * cosAlt - dx * sinAz * cosAlt)) / sqrt(1.0 + dx * dx + dy * dy)
    val shade = cang.coerceIn(0.0, 1.0)
    return if (shade > 0.5) {
        source.highlight.premultipliedTimes((2.0 * shade - 1.0) * weight)
    } else {
        source.shadow.premultipliedTimes((1.0 - 2.0 * shade) * weight)
    }
}

/** `combined_hillshade`, after `GDALHillshadeCombinedAlg`: `basic`'s angle scaled by the slope. */
private fun combinedHillshade(
    derivX: Double,
    derivY: Double,
    exaggeration: Double,
    source: IlluminationSource,
): Premultiplied {
    val dx = derivX * exaggeration * 2.0
    val dy = derivY * exaggeration * 2.0
    val azimuth = source.azimuthRad + PI
    val cosAz = cos(azimuth)
    val sinAz = sin(azimuth)
    val cosAlt = cos(source.altitudeRad)
    val sinAlt = sin(source.altitudeRad)

    val ratio = (sinAlt - (dy * cosAz * cosAlt - dx * sinAz * cosAlt)) / sqrt(1.0 + dx * dx + dy * dy)
    val cang = acos(ratio.coerceIn(-1.0, 1.0)).coerceIn(0.0, PI / 2.0)

    val slope = atan(sqrt(dx * dx + dy * dy)) * 4.0 / PI / PI
    val shade = cang * slope
    val highlight = (PI / 2.0 - cang) * slope

    return source.shadow.premultipliedTimes(shade)
        .plus(source.highlight.premultipliedTimes(highlight))
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
