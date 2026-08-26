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

    @Test
    fun `slopeDivisor matches upstream at a 512 pixel dem`() {
        // Upstream's literal: pow(2, exaggeration + (19.2562 - z)), where 19.2562 hardcodes 512.
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
    fun `flat ground has no slope and is not shaded`() {
        val dem = demOf(8) { _, _ -> 100 }
        val divisor = slopeDivisor(tileZoom = 12.0, demZoom = 12, dim = 8)

        val (dx, dy) = sobelDeriv(dem, x = 4, y = 4, divisor = divisor)
        assertEquals(0.0, dx, 1e-12)
        assertEquals(0.0, dy, 1e-12)

        val color = shadePixel(
            derivX = dx, derivY = dy, latitude = 0.0, intensity = 0.5, azimuthRad = 0.0,
            shadow = Color.Black, highlight = Color.White, accent = Color.Black,
        )
        assertEquals(0f, color.alpha, "a flat sample must leave the tile untouched")
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

        assertEquals(1.0, dx, 1e-12, "upstream packs the derivative into 8 bits as deriv/2 + 0.5")
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
        val highlight = Color.Green
        val shadow = Color.Red

        fun shade(derivY: Double) = shadePixel(
            derivX = 0.0, derivY = derivY, latitude = 0.0, intensity = 0.5, azimuthRad = 0.0,
            shadow = shadow, highlight = highlight, accent = Color.Black,
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
        val highlight = Color.Green
        val shadow = Color.Red

        fun shade(azimuthDeg: Double) = shadePixel(
            derivX = 0.0, derivY = 0.5, latitude = 0.0, intensity = 0.5,
            azimuthRad = azimuthDeg * PI / 180.0,
            shadow = shadow, highlight = highlight, accent = Color.Black,
        )

        assertTrue(shade(0.0).green > shade(0.0).red)
        assertTrue(shade(180.0).red > shade(180.0).green)
    }

    @Test
    fun `a lower intensity makes the whole layer more transparent`() {
        fun alphaAt(intensity: Double) = shadePixel(
            derivX = 0.4, derivY = 0.4, latitude = 0.0, intensity = intensity, azimuthRad = 0.0,
            shadow = Color.Black, highlight = Color.White, accent = Color.Black,
        ).alpha

        assertTrue(alphaAt(0.1) < alphaAt(0.25), "below 0.5 the intensity scales the colours")
        assertTrue(alphaAt(0.25) < alphaAt(0.5))
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
