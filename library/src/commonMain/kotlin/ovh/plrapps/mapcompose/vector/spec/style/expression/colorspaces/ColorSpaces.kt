package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * sRGB <-> CIE LAB and HCL conversions, used by `interpolate-lab` and `interpolate-hcl`.
 *
 * Ported from `maplibre-style-spec/src/expression/types/color_spaces.ts`
 * (see https://observablehq.com/@mbostock/lab-and-rgb). The D50 white point and the exact matrix
 * coefficients are kept as-is so interpolated colors match MapLibre's output bit for bit.
 */

/** `[l, a, b, alpha]` — lightness 0..100, a/b axes -125..125, alpha 0..1. */
typealias LabColor = DoubleArray

/** `[h, c, l, alpha]` — hue degrees 0..360 (NaN when achromatic), chroma 0..~230, lightness 0..100. */
typealias HclColor = DoubleArray

/** `[r, g, b, alpha]`, all 0..1. */
typealias RgbColor = DoubleArray

private const val Xn = 0.96422
private const val Yn = 1.0
private const val Zn = 0.82521
private const val T0 = 4.0 / 29.0
private const val T1 = 6.0 / 29.0
private const val T2 = 3.0 * T1 * T1
private const val T3 = T1 * T1 * T1
private const val DEG_2_RAD = PI / 180.0
private const val RAD_2_DEG = 180.0 / PI

private fun constrainAngle(angleIn: Double): Double {
    var angle = angleIn % 360.0
    if (angle < 0) angle += 360.0
    return angle
}

private fun rgb2xyz(x: Double): Double = if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)

private fun xyz2lab(t: Double): Double = if (t > T3) t.pow(1.0 / 3.0) else t / T2 + T0

private fun lab2xyz(t: Double): Double = if (t > T1) t * t * t else T2 * (t - T0)

private fun xyz2rgb(xIn: Double): Double {
    val x = if (xIn <= 0.00304) 12.92 * xIn else 1.055 * xIn.pow(1.0 / 2.4) - 0.055
    return if (x < 0) 0.0 else if (x > 1) 1.0 else x
}

fun rgbToLab(rgb: RgbColor): LabColor {
    val r = rgb2xyz(rgb[0])
    val g = rgb2xyz(rgb[1])
    val b = rgb2xyz(rgb[2])
    val alpha = rgb[3]

    val y = xyz2lab((0.2225045 * r + 0.7168786 * g + 0.0606169 * b) / Yn)
    val x: Double
    val z: Double
    if (r == g && g == b) {
        x = y
        z = y
    } else {
        x = xyz2lab((0.4360747 * r + 0.3850649 * g + 0.1430804 * b) / Xn)
        z = xyz2lab((0.0139322 * r + 0.0971045 * g + 0.7141733 * b) / Zn)
    }

    val l = 116 * y - 16
    return doubleArrayOf(if (l < 0) 0.0 else l, 500 * (x - y), 200 * (y - z), alpha)
}

fun labToRgb(lab: LabColor): RgbColor {
    val l = lab[0]
    val a = lab[1]
    val b = lab[2]
    val alpha = lab[3]

    var y = (l + 16) / 116
    var x = if (a.isNaN()) y else y + a / 500
    var z = if (b.isNaN()) y else y - b / 200

    y = Yn * lab2xyz(y)
    x = Xn * lab2xyz(x)
    z = Zn * lab2xyz(z)

    return doubleArrayOf(
        xyz2rgb(3.1338561 * x - 1.6168667 * y - 0.4906146 * z), // D50 -> sRGB
        xyz2rgb(-0.9787684 * x + 1.9161415 * y + 0.033454 * z),
        xyz2rgb(0.0719453 * x - 0.2289914 * y + 1.4052427 * z),
        alpha,
    )
}

fun rgbToHcl(rgb: RgbColor): HclColor {
    val lab = rgbToLab(rgb)
    val a = lab[1]
    val b = lab[2]
    val c = sqrt(a * a + b * b)
    val h = if (round(c * 10000) != 0.0) constrainAngle(atan2(b, a) * RAD_2_DEG) else Double.NaN
    return doubleArrayOf(h, c, lab[0], lab[3])
}

fun hclToRgb(hcl: HclColor): RgbColor {
    val h = if (hcl[0].isNaN()) 0.0 else hcl[0] * DEG_2_RAD
    val c = hcl[1]
    return labToRgb(doubleArrayOf(hcl[2], cos(h) * c, sin(h) * c, hcl[3]))
}

fun Color.toRgbArray(): RgbColor =
    doubleArrayOf(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

fun rgbArrayToColor(rgb: RgbColor): Color =
    Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat(), rgb[3].toFloat())
