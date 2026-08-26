package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests [rasterColorMatrix] against the maths of maplibre-gl-js `src/render/draw_raster.ts` and
 * `src/shaders/raster.fragment.glsl`.
 *
 * Pure matrix arithmetic, so it lives in `commonTest` and runs on every target -- unlike the painter
 * tests, which need a real `ImageBitmap` and so sit in `skiaTest`.
 */
class RasterColorMatrixTest {

    private fun identityChannel(row: Int, column: Int): Float = if (row == column) 1f else 0f

    @Test
    fun `all defaults need no colour filter at all`() {
        assertNull(
            rasterColorMatrix(
                hueRotate = 0f, saturation = 0f, contrast = 0f,
                brightnessMin = 0f, brightnessMax = 1f,
            )
        )
    }

    @Test
    fun `any non default adjustment produces a matrix`() {
        assertNotNull(rasterColorMatrix(1f, 0f, 0f, 0f, 1f), "hue-rotate")
        assertNotNull(rasterColorMatrix(0f, 0.5f, 0f, 0f, 1f), "saturation")
        assertNotNull(rasterColorMatrix(0f, 0f, 0.5f, 0f, 1f), "contrast")
        assertNotNull(rasterColorMatrix(0f, 0f, 0f, 0.5f, 1f), "brightness-min")
        assertNotNull(rasterColorMatrix(0f, 0f, 0f, 0f, 0.5f), "brightness-max")
    }

    @Test
    fun `spin weights match upstream at the cardinal angles`() {
        // spinWeights(0) is the identity mix - all of red stays red.
        val zero = spinWeights(0f)
        assertEquals(1f, zero[0], absoluteTolerance = 1e-5f)
        assertEquals(0f, zero[1], absoluteTolerance = 1e-5f)
        assertEquals(0f, zero[2], absoluteTolerance = 1e-5f)

        // At 120 degrees the channels rotate exactly one position.
        val third = spinWeights(120f)
        assertEquals(0f, third[0], absoluteTolerance = 1e-5f)
        assertEquals(0f, third[1], absoluteTolerance = 1e-5f)
        assertEquals(1f, third[2], absoluteTolerance = 1e-5f)

        // Every angle's weights sum to 1, which is what keeps grey grey.
        for (angle in listOf(0f, 37f, 90f, 180f, 271f, 359f)) {
            val w = spinWeights(angle)
            assertEquals(1f, w[0] + w[1] + w[2], absoluteTolerance = 1e-5f, message = "angle $angle")
        }
    }

    @Test
    fun `contrast and saturation factors match upstream`() {
        assertEquals(1f, contrastFactor(0f), absoluteTolerance = 1e-6f)
        assertEquals(0.5f, contrastFactor(-0.5f), absoluteTolerance = 1e-6f)
        assertEquals(2f, contrastFactor(0.5f), absoluteTolerance = 1e-6f)

        assertEquals(0f, saturationFactor(0f), absoluteTolerance = 1e-6f)
        assertEquals(0.5f, saturationFactor(-0.5f), absoluteTolerance = 1e-6f)
        // s > 0 uses 1 - 1 / (1.001 - s), which is negative - it pushes channels apart.
        assertEquals(1f - 1f / 0.501f, saturationFactor(0.5f), absoluteTolerance = 1e-6f)
    }

    @Test
    fun `a full hue rotation is the identity`() {
        val matrix = assertNotNull(rasterColorMatrix(360f, 0f, 0f, 0f, 1f))
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(
                    identityChannel(row, column),
                    matrix.values[row * 5 + column],
                    absoluteTolerance = 1e-4f,
                    message = "row $row column $column",
                )
            }
        }
    }

    @Test
    fun `hue rotation by 120 degrees cycles the channels`() {
        val matrix = assertNotNull(rasterColorMatrix(120f, 0f, 0f, 0f, 1f))
        // Red output reads blue in, green reads red, blue reads green.
        assertEquals(1f, matrix.values[2], absoluteTolerance = 1e-4f, message = "R' from B")
        assertEquals(1f, matrix.values[5], absoluteTolerance = 1e-4f, message = "G' from R")
        assertEquals(1f, matrix.values[11], absoluteTolerance = 1e-4f, message = "B' from G")
    }

    @Test
    fun `full desaturation averages the three channels`() {
        val matrix = assertNotNull(rasterColorMatrix(0f, -1f, 0f, 0f, 1f))
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(
                    1f / 3f,
                    matrix.values[row * 5 + column],
                    absoluteTolerance = 1e-5f,
                    message = "row $row column $column",
                )
            }
        }
    }

    @Test
    fun `brightness maps the range onto gain and offset`() {
        // brightness-min 0.25, brightness-max 0.75: rgb = 0.25 + rgb * 0.5
        val matrix = assertNotNull(rasterColorMatrix(0f, 0f, 0f, 0.25f, 0.75f))
        assertEquals(0.5f, matrix.values[0], absoluteTolerance = 1e-5f, message = "gain")
        // The translation column is in 0..255, not 0..1 - see the function's KDoc.
        assertEquals(0.25f * 255f, matrix.values[4], absoluteTolerance = 1e-3f, message = "offset")
        assertEquals(matrix.values[4], matrix.values[9], absoluteTolerance = 1e-5f)
        assertEquals(matrix.values[4], matrix.values[14], absoluteTolerance = 1e-5f)
    }

    @Test
    fun `contrast pivots around one half`() {
        // contrast 0.5 gives a factor of 2, so rgb = (rgb - 0.5) * 2 + 0.5 = rgb * 2 - 0.5
        val matrix = assertNotNull(rasterColorMatrix(0f, 0f, 0.5f, 0f, 1f))
        assertEquals(2f, matrix.values[0], absoluteTolerance = 1e-5f, message = "gain")
        assertEquals(-0.5f * 255f, matrix.values[4], absoluteTolerance = 1e-3f, message = "offset")
    }

    @Test
    fun `alpha is never touched`() {
        val matrix = assertNotNull(rasterColorMatrix(45f, 0.3f, -0.2f, 0.1f, 0.9f))
        assertEquals(0f, matrix.values[15], absoluteTolerance = 1e-6f)
        assertEquals(0f, matrix.values[16], absoluteTolerance = 1e-6f)
        assertEquals(0f, matrix.values[17], absoluteTolerance = 1e-6f)
        assertEquals(1f, matrix.values[18], absoluteTolerance = 1e-6f)
        assertEquals(0f, matrix.values[19], absoluteTolerance = 1e-6f)
        // The colour rows never read alpha either.
        assertEquals(0f, matrix.values[3], absoluteTolerance = 1e-6f)
        assertEquals(0f, matrix.values[8], absoluteTolerance = 1e-6f)
        assertEquals(0f, matrix.values[13], absoluteTolerance = 1e-6f)
    }

    @Test
    fun `a hue rotation keeps grey grey`() {
        // Every row of a pure spin sums to 1, so an equal-channel colour comes out unchanged.
        val matrix = assertNotNull(rasterColorMatrix(53f, 0f, 0f, 0f, 1f))
        for (row in 0..2) {
            val sum = matrix.values[row * 5] + matrix.values[row * 5 + 1] + matrix.values[row * 5 + 2]
            assertEquals(1f, sum, absoluteTolerance = 1e-5f, message = "row $row")
        }
    }
}
