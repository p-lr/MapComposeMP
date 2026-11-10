package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Transcribed from `maplibre-style-spec/src/expression/types/color_spaces.test.ts`.
 *
 * `expectCloseToArray(actual, expected, digits)` compares to `digits` decimal places, defaulting to
 * 2 in upstream's helper; [assertCloseToArray] reproduces that, including its NaN-equals-NaN rule,
 * which the achromatic HCL cases rely on.
 */
class ColorSpacesUpstreamTest {

    private fun assertCloseToArray(actual: DoubleArray, expected: DoubleArray, digits: Int = 2) {
        val tolerance = 10.0.pow(-digits) / 2
        assertTrue(actual.size == expected.size, "expected ${expected.toList()}, got ${actual.toList()}")
        for (i in expected.indices) {
            val a = actual[i]
            val e = expected[i]
            val ok = (a.isNaN() && e.isNaN()) || abs(a - e) < tolerance
            assertTrue(ok, "index $i: expected ${expected.toList()}, got ${actual.toList()}")
        }
    }

    // region LAB colour space

    @Test
    fun `should convert colors from sRGB to LAB color space`() {
        assertCloseToArray(rgbToLab(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(rgbToLab(doubleArrayOf(1.0, 1.0, 1.0, 1.0)), doubleArrayOf(100.0, 0.0, 0.0, 1.0), 4)
        assertCloseToArray(rgbToLab(doubleArrayOf(0.0, 1.0, 0.0, 1.0)), doubleArrayOf(87.82, -79.29, 80.99, 1.0), 2)
        assertCloseToArray(rgbToLab(doubleArrayOf(0.0, 1.0, 1.0, 1.0)), doubleArrayOf(90.67, -50.67, -14.96, 1.0), 2)
        assertCloseToArray(rgbToLab(doubleArrayOf(0.0, 0.0, 1.0, 1.0)), doubleArrayOf(29.57, 68.3, -112.03, 1.0), 2)
        assertCloseToArray(rgbToLab(doubleArrayOf(1.0, 1.0, 0.0, 1.0)), doubleArrayOf(97.61, -15.75, 93.39, 1.0), 2)
        assertCloseToArray(rgbToLab(doubleArrayOf(1.0, 0.0, 0.0, 1.0)), doubleArrayOf(54.29, 80.81, 69.89, 1.0), 2)
    }

    @Test
    fun `should convert colors from LAB to sRGB color space`() {
        assertCloseToArray(labToRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(labToRgb(doubleArrayOf(100.0, 0.0, 0.0, 1.0)), doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertCloseToArray(
            labToRgb(doubleArrayOf(50.0, 50.0, 0.0, 1.0)),
            doubleArrayOf(0.7562, 0.3045, 0.4756, 1.0), 4,
        )
        assertCloseToArray(
            labToRgb(doubleArrayOf(70.0, -45.0, 0.0, 1.0)),
            doubleArrayOf(0.1079, 0.7556, 0.664, 1.0), 4,
        )
        assertCloseToArray(
            labToRgb(doubleArrayOf(70.0, 0.0, 70.0, 1.0)),
            doubleArrayOf(0.7663, 0.6636, 0.0558, 1.0), 4,
        )
        assertCloseToArray(
            labToRgb(doubleArrayOf(55.0, 0.0, -60.0, 1.0)),
            doubleArrayOf(0.1281, 0.531, 0.9276, 1.0), 4,
        )
        assertCloseToArray(
            labToRgb(doubleArrayOf(29.57, 68.3, -112.03, 1.0)),
            doubleArrayOf(0.0, 0.0, 1.0, 1.0), 3,
        )
    }

    // endregion

    // region HCL colour space

    @Test
    fun `should convert colors from sRGB to HCL color space`() {
        assertCloseToArray(rgbToHcl(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), doubleArrayOf(Double.NaN, 0.0, 0.0, 1.0))
        assertCloseToArray(
            rgbToHcl(doubleArrayOf(1.0, 1.0, 1.0, 1.0)),
            doubleArrayOf(Double.NaN, 0.0, 100.0, 1.0), 4,
        )
        assertCloseToArray(rgbToHcl(doubleArrayOf(0.0, 1.0, 0.0, 1.0)), doubleArrayOf(134.39, 113.34, 87.82, 1.0), 2)
        assertCloseToArray(rgbToHcl(doubleArrayOf(0.0, 1.0, 1.0, 1.0)), doubleArrayOf(196.45, 52.83, 90.67, 1.0), 2)
        assertCloseToArray(rgbToHcl(doubleArrayOf(0.0, 0.0, 1.0, 1.0)), doubleArrayOf(301.37, 131.21, 29.57, 1.0), 2)
        assertCloseToArray(rgbToHcl(doubleArrayOf(1.0, 1.0, 0.0, 1.0)), doubleArrayOf(99.57, 94.71, 97.61, 1.0), 2)
        assertCloseToArray(rgbToHcl(doubleArrayOf(1.0, 0.0, 0.0, 1.0)), doubleArrayOf(40.85, 106.84, 54.29, 1.0), 2)
    }

    @Test
    fun `should convert colors from HCL to sRGB color space`() {
        assertCloseToArray(hclToRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(hclToRgb(doubleArrayOf(0.0, 0.0, 100.0, 1.0)), doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertCloseToArray(
            hclToRgb(doubleArrayOf(0.0, 50.0, 50.0, 1.0)),
            doubleArrayOf(0.7562, 0.3045, 0.4756, 1.0), 4,
        )
        assertCloseToArray(
            hclToRgb(doubleArrayOf(180.0, 45.0, 70.0, 1.0)),
            doubleArrayOf(0.1079, 0.7556, 0.664, 1.0), 4,
        )
        assertCloseToArray(
            hclToRgb(doubleArrayOf(90.0, 70.0, 70.0, 1.0)),
            doubleArrayOf(0.7663, 0.6636, 0.0558, 1.0), 4,
        )
        assertCloseToArray(
            hclToRgb(doubleArrayOf(270.0, 60.0, 55.0, 1.0)),
            doubleArrayOf(0.1281, 0.531, 0.9276, 1.0), 4,
        )
        assertCloseToArray(
            hclToRgb(doubleArrayOf(301.37, 131.21, 29.57, 1.0)),
            doubleArrayOf(0.0, 0.0, 1.0, 1.0), 3,
        )
    }

    // endregion

    @Test
    fun `should convert colors from HSL to sRGB color space`() {
        assertCloseToArray(hslToRgb(doubleArrayOf(0.0, 0.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(hslToRgb(doubleArrayOf(0.0, 100.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(hslToRgb(doubleArrayOf(0.0, 0.0, 100.0, 1.0)), doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertCloseToArray(hslToRgb(doubleArrayOf(360.0, 0.0, 0.0, 1.0)), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertCloseToArray(
            hslToRgb(doubleArrayOf(120.0, 100.0, 25.0, 1.0)),
            doubleArrayOf(0.0, 128 / 255.0, 0.0, 1.0), 2,
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(120.0, 30.0, 50.0, 0.0)),
            doubleArrayOf(89 / 255.0, 166 / 255.0, 89 / 255.0, 0.0), 2,
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(240.0, 25.0, 50.0, 0.1)),
            doubleArrayOf(96 / 255.0, 96 / 255.0, 159 / 255.0, 0.1), 2,
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(240.0, 50.0, 50.0, 0.8)),
            doubleArrayOf(64 / 255.0, 64 / 255.0, 191 / 255.0, 0.8), 2,
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(270.0, 75.0, 75.0, 1.0)),
            doubleArrayOf(191 / 255.0, 143 / 255.0, 239 / 255.0, 1.0), 2,
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(300.0, 100.0, 50.0, 0.5)),
            doubleArrayOf(1.0, 0.0, 1.0, 0.5),
        )
        assertCloseToArray(
            hslToRgb(doubleArrayOf(330.0, 0.0, 25.0, 0.3)),
            doubleArrayOf(64 / 255.0, 64 / 255.0, 64 / 255.0, 0.3), 2,
        )
    }
}
