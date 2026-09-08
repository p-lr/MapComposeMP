package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.DemUnpack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the two shader passes ported into `HillshadeShading.kt`.
 *
 * Pure maths, so `commonTest` rather than `skiaTest` -- the same split
 * [ovh.plrapps.mapcompose.vector.renderer.utils.rasterColorMatrix] and its test use.
 */
class HillshadeShadingTest {

    /** One elevation per sample, encoded so that the blue channel is metres. */
    private val metresPerBlue = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)

    private fun demOf(dim: Int, elevation: (x: Int, y: Int) -> Int): DemData {
        val pixels = IntArray(dim * dim) { i ->
            val value = elevation(i % dim, i / dim).coerceIn(0, 255)
            (0xFF shl 24) or value
        }
        return assertNotNull(DemData.fromArgb(pixels, dim, dim, metresPerBlue))
    }

    private fun sourceOf(
        directionDeg: Double = 0.0,
        altitudeDeg: Double = 45.0,
        shadow: Color = Color.Black,
        highlight: Color = Color.White,
    ) = IlluminationSource(
        azimuthRad = directionDeg * PI / 180.0,
        altitudeRad = altitudeDeg * PI / 180.0,
        shadow = shadow,
        highlight = highlight,
    )

    @Test
    fun `slopeDivisor matches upstream at a 512 pixel dem`() {
        /* Upstream's divisor is `pow(2, exaggeration + (28.2562 - z)) / tileSize`, which at a 512 px
         * tile is the `pow(2, exaggeration + (19.2562 - z))` its prepare shader used to hardcode. */
        for (z in listOf(0.0, 1.0, 3.0, 6.0, 12.0, 15.0, 18.0)) {
            val exaggerationFactor = when {
                z < 2.0 -> 0.4
                z < 4.5 -> 0.35
                else -> 0.3
            }
            val exaggeration = if (z < 15.0) (z - 15.0) * exaggerationFactor else 0.0
            val upstream = 2.0.pow(exaggeration + (19.2562 - z))

            val actual = slopeDivisor(tileZoom = z, demZoom = z.toInt(), dim = 512)
            assertTrue(
                abs(actual - upstream) / upstream < 1e-4,
                "at zoom $z expected about $upstream but was $actual",
            )
        }
    }

    @Test
    fun `a 256 pixel dem doubles the ground resolution of a 512 pixel one`() {
        val coarse = metersPerPixel(demZoom = 10, dim = 256)
        val fine = metersPerPixel(demZoom = 10, dim = 512)

        assertEquals(2.0, coarse / fine, 1e-9)
    }

    @Test
    fun `the derivative is the true gradient and is not quartered`() {
        /* maplibre-gl-js#5768 dropped the `/ 4.0` its prepare pass applied to every elevation, so
         * the Sobel sum reaches the shader undivided -- which is the half of the rescaling this
         * port had to follow, the other half being the z-factor halving from 1.25 to 0.625. */
        val dem = demOf(8) { x, _ -> x * 10 }
        val divisor = 1000.0

        val (dx, _) = sobelDeriv(dem, x = 4, y = 4, divisor = divisor)

        /* The neighbourhood is a 10 m per sample ramp, so the weighted difference is 4 * 2 * 10. */
        assertEquals(80.0 / divisor, dx, 1e-12)
    }

    @Test
    fun `flat ground has no slope`() {
        val dem = demOf(8) { _, _ -> 100 }
        val divisor = slopeDivisor(tileZoom = 12.0, demZoom = 12, dim = 8)

        val (dx, dy) = sobelDeriv(dem, x = 4, y = 4, divisor = divisor)
        assertEquals(0.0, dx, 1e-12)
        assertEquals(0.0, dy, 1e-12)
    }

    @Test
    fun `an east-west ramp tilts the derivative on x only`() {
        val dem = demOf(8) { x, _ -> x * 10 }
        val divisor = slopeDivisor(tileZoom = 14.0, demZoom = 14, dim = 8)

        val (dx, dy) = sobelDeriv(dem, x = 4, y = 4, divisor = divisor)
        assertTrue(dx > 0.0, "elevation rises eastward so the x derivative is positive")
        assertEquals(0.0, dy, 1e-12)
    }

    @Test
    fun `a north-south ramp tilts the derivative on y only`() {
        val dem = demOf(8) { _, y -> y * 10 }
        val divisor = slopeDivisor(tileZoom = 14.0, demZoom = 14, dim = 8)

        val (dx, dy) = sobelDeriv(dem, x = 4, y = 4, divisor = divisor)
        assertEquals(0.0, dx, 1e-12)
        assertTrue(dy > 0.0)
    }

    @Test
    fun `the derivative is clamped so a cliff cannot shade beyond the maximum`() {
        val dem = demOf(8) { x, _ -> if (x < 4) 0 else 255 }
        // A tiny divisor stands in for a very high zoom over very steep ground.
        val (dx, dy) = sobelDeriv(dem, x = 4, y = 4, divisor = 1e-3)

        assertEquals(4.0, dx, 1e-12, "upstream packs the derivative into 8 bits as deriv/8 + 0.5")
        assertEquals(0.0, dy, 1e-12)
    }

    @Test
    fun `the border ring is what makes an edge sample agree with its neighbour`() {
        // One ramp of 10 m per sample, split across two tiles: this tile holds its eastern half.
        val dem = demOf(8) { x, _ -> (x + 8) * 10 }
        val divisor = slopeDivisor(tileZoom = 14.0, demZoom = 14, dim = 8)

        val interior = sobelDeriv(dem, x = 4, y = 4, divisor = divisor).first
        val edgeWithClampedSeed = sobelDeriv(dem, x = 0, y = 4, divisor = divisor).first
        assertTrue(
            edgeWithClampedSeed < interior,
            "with only the clamped seed the edge slope is understated -- this is the seam",
        )

        val west = demOf(8) { x, _ -> x * 10 }
        dem.backfillBorder(west, dx = -1, dy = 0)
        val edgeBackfilled = sobelDeriv(dem, x = 0, y = 4, divisor = divisor).first
        assertEquals(
            interior, edgeBackfilled, 1e-9,
            "once the neighbour is stitched on, the edge reads the same slope as the interior",
        )
    }

    @Test
    fun `a slope facing the light is highlighted and one facing away is shadowed`() {
        // Light from due north (0 degrees), tinted so the two cases are unmistakable.
        val source = sourceOf(directionDeg = 0.0, shadow = Color.Red, highlight = Color.Green)

        fun shade(derivY: Double) = shadePixel(
            derivX = 0.0, derivY = derivY, latitude = 0.0, exaggeration = 0.5,
            method = HillshadeMethod.STANDARD, sources = listOf(source), accent = Color.Black,
        )

        /* deriv.y is south minus north in DEM row order, so a positive value means the ground
         * rises southward -- that is, the slope faces north, into the light. */
        val facingNorth = shade(0.5)
        val facingSouth = shade(-0.5)

        assertTrue(
            facingNorth.green > facingNorth.red,
            "a north-facing slope takes the highlight but was $facingNorth",
        )
        assertTrue(
            facingSouth.red > facingSouth.green,
            "a south-facing slope takes the shadow but was $facingSouth",
        )
    }

    @Test
    fun `turning the light around swaps which side is lit`() {
        fun shade(azimuthDeg: Double) = shadePixel(
            derivX = 0.0, derivY = 0.5, latitude = 0.0, exaggeration = 0.5,
            method = HillshadeMethod.STANDARD,
            sources = listOf(
                sourceOf(directionDeg = azimuthDeg, shadow = Color.Red, highlight = Color.Green)
            ),
            accent = Color.Black,
        )

        assertTrue(shade(0.0).green > shade(0.0).red)
        assertTrue(shade(180.0).red > shade(180.0).green)
    }

    @Test
    fun `a lower intensity makes the whole layer more transparent`() {
        fun alphaAt(intensity: Double) = shadePixel(
            derivX = 0.4, derivY = 0.4, latitude = 0.0, exaggeration = intensity,
            method = HillshadeMethod.STANDARD, sources = listOf(sourceOf()), accent = Color.Black,
        ).alpha

        assertTrue(alphaAt(0.1) < alphaAt(0.25), "below 0.5 the intensity scales the colours")
        assertTrue(alphaAt(0.25) < alphaAt(0.5))
    }

    @Test
    fun `an unknown method falls back to standard as upstream's default arm does`() {
        assertEquals(HillshadeMethod.STANDARD, HillshadeMethod.ofOrDefault(null))
        assertEquals(HillshadeMethod.STANDARD, HillshadeMethod.ofOrDefault("standard"))
        assertEquals(HillshadeMethod.STANDARD, HillshadeMethod.ofOrDefault("not-a-method"))
        assertEquals(HillshadeMethod.IGOR, HillshadeMethod.ofOrDefault("igor"))
        assertEquals(
            HillshadeMethod.MULTIDIRECTIONAL,
            HillshadeMethod.ofOrDefault("multidirectional"),
        )
    }

    @Test
    fun `only the methods that ignore the light altitude leave flat ground untouched`() {
        fun flat(method: HillshadeMethod) = shadePixel(
            derivX = 0.0, derivY = 0.0, latitude = 0.0, exaggeration = 0.5,
            method = method, sources = listOf(sourceOf()), accent = Color.Black,
        )

        /* sin(0) and 1 - cos(0) are both zero, so these three vanish on flat ground. */
        assertEquals(0f, flat(HillshadeMethod.STANDARD).alpha)
        assertEquals(0f, flat(HillshadeMethod.IGOR).alpha)
        assertEquals(0f, flat(HillshadeMethod.COMBINED).alpha)

        /* `basic` and `multidirectional` light flat ground by cos of the altitude, which at the
         * spec's 45 degrees clears the shader's 0.5 threshold -- so it is a uniform highlight, not
         * nothing, and the painter cannot short-circuit it away. */
        assertTrue(
            flat(HillshadeMethod.BASIC).alpha > 0f,
            "a flat surface under a 45 degree light is lit, not transparent",
        )
        assertTrue(flat(HillshadeMethod.MULTIDIRECTIONAL).alpha > 0f)
    }

    @Test
    fun `a light directly overhead saturates the highlight on flat ground`() {
        val overhead = shadePixel(
            derivX = 0.0, derivY = 0.0, latitude = 0.0, exaggeration = 0.5,
            method = HillshadeMethod.BASIC,
            sources = listOf(sourceOf(altitudeDeg = 90.0, highlight = Color.Green)),
            accent = Color.Black,
        )

        // cang is sin(90) = 1, so the highlight weight is 2 * 1 - 1.
        assertEquals(1f, overhead.alpha, 1e-6f)
        assertEquals(Color.Green.green, overhead.green, 1e-6f)
    }

    @Test
    fun `multidirectional over one source is basic`() {
        val source = sourceOf(directionDeg = 335.0, altitudeDeg = 30.0)

        fun shade(method: HillshadeMethod) = shadePixel(
            derivX = 0.3, derivY = -0.2, latitude = 46.0, exaggeration = 0.7,
            method = method, sources = listOf(source), accent = Color.Black,
        )

        /* Upstream negates multidirectional's cos_az and sin_az and drops basic's `+ PI`, which is
         * the same rotation -- so the two must agree exactly for a single light. */
        assertEquals(shade(HillshadeMethod.BASIC), shade(HillshadeMethod.MULTIDIRECTIONAL))
    }

    @Test
    fun `multidirectional averages its sources`() {
        val east = sourceOf(directionDeg = 90.0)
        val west = sourceOf(directionDeg = 270.0)

        fun alpha(sources: List<IlluminationSource>) = shadePixel(
            derivX = 0.6, derivY = 0.0, latitude = 0.0, exaggeration = 0.6,
            method = HillshadeMethod.MULTIDIRECTIONAL, sources = sources, accent = Color.Black,
        ).alpha

        val fromEast = alpha(listOf(east))
        val fromWest = alpha(listOf(west))
        val fromBoth = alpha(listOf(east, west))

        /* An east-facing slope is shadowed by one light and lit by the other, so their weights
         * differ -- and the pair's result is the mean of the two, which is what upstream's
         * `/ float(NUM_ILLUMINATION_SOURCES)` computes. */
        assertTrue(fromEast != fromWest, "the two lights must disagree for the mean to mean anything")
        // Compose quantizes alpha to 8 bits, so the mean of two rounded values and the rounded
        // mean can sit half a step apart.
        assertEquals((fromEast + fromWest) / 2f, fromBoth, 1f / 255f)
    }

    @Test
    fun `igor shades by aspect and ignores the light altitude`() {
        fun shade(altitudeDeg: Double) = shadePixel(
            derivX = 0.0, derivY = 0.5, latitude = 0.0, exaggeration = 0.5,
            method = HillshadeMethod.IGOR,
            sources = listOf(sourceOf(altitudeDeg = altitudeDeg)),
            accent = Color.Black,
        )

        assertEquals(shade(20.0), shade(70.0))
    }

    @Test
    fun `only standard reads the accent colour`() {
        fun shade(method: HillshadeMethod, accent: Color) = shadePixel(
            derivX = 0.4, derivY = 0.4, latitude = 0.0, exaggeration = 0.5,
            method = method, sources = listOf(sourceOf()), accent = accent,
        )

        assertTrue(
            shade(HillshadeMethod.STANDARD, Color.Red) != shade(HillshadeMethod.STANDARD, Color.Blue)
        )
        for (method in listOf(
            HillshadeMethod.BASIC,
            HillshadeMethod.COMBINED,
            HillshadeMethod.IGOR,
            HillshadeMethod.MULTIDIRECTIONAL,
        )) {
            assertEquals(shade(method, Color.Red), shade(method, Color.Blue), "$method")
        }
    }

    @Test
    fun `illuminationSources pads every short list with its own last element`() {
        val sources = illuminationSources(
            directionsDeg = listOf(0.0, 90.0, 180.0),
            altitudesDeg = listOf(30.0),
            shadows = listOf(Color.Red, Color.Green),
            highlights = emptyList(),
            fallbackDirectionDeg = 335.0,
            fallbackAltitudeDeg = 45.0,
            fallbackShadow = Color.Black,
            fallbackHighlight = Color.White,
        )

        assertEquals(3, sources.size, "the longest list decides the source count")
        assertEquals(listOf(0.0, 90.0, 180.0), sources.map { it.azimuthRad * 180.0 / PI })
        assertTrue(sources.all { abs(it.altitudeRad - 30.0 * PI / 180.0) < 1e-12 })
        assertEquals(listOf(Color.Red, Color.Green, Color.Green), sources.map { it.shadow })
        assertEquals(List(3) { Color.White }, sources.map { it.highlight })
    }

    @Test
    fun `illuminationSources falls back to the spec defaults when a property is absent`() {
        val sources = illuminationSources(
            directionsDeg = emptyList(),
            altitudesDeg = emptyList(),
            shadows = emptyList(),
            highlights = emptyList(),
            fallbackDirectionDeg = 335.0,
            fallbackAltitudeDeg = 45.0,
            fallbackShadow = Color.Black,
            fallbackHighlight = Color.White,
        )

        assertEquals(1, sources.size)
        assertEquals(335.0, sources[0].azimuthRad * 180.0 / PI, 1e-9)
        assertEquals(45.0, sources[0].altitudeRad * 180.0 / PI, 1e-9)
    }

    @Test
    fun `mercator latitude spans the web mercator limits`() {
        val (top, bottom) = tileLatRange(z = 0, y = 0)

        assertEquals(85.0511, top, 1e-3)
        assertEquals(-85.0511, bottom, 1e-3)
        assertEquals(0.0, mercatorYToLatitude(0.5), 1e-9)
    }

    @Test
    fun `a tile row knows its own latitude band`() {
        val (top, bottom) = tileLatRange(z = 2, y = 2)

        assertEquals(0.0, top, 1e-9, "row 2 of 4 starts at the equator")
        assertTrue(bottom < top, "and runs south from there")
    }
}
