package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import androidx.compose.ui.graphics.Color

/** The color spaces `interpolate`, `interpolate-hcl` and `interpolate-lab` operate in. */
enum class InterpolationColorSpace { RGB, HCL, LAB }

fun interpolateNumber(from: Double, to: Double, t: Double): Double = from + t * (to - from)

fun interpolateArray(from: DoubleArray, to: DoubleArray, t: Double): DoubleArray =
    DoubleArray(from.size) { i -> interpolateNumber(from[i], to[i], t) }

/**
 * Ported from `Color.interpolate` in `maplibre-style-spec/src/expression/types/color.ts`.
 *
 * The HCL branch reproduces chroma.js's hue-shortest-path rule, including its handling of the
 * achromatic (NaN hue) cases.
 */
fun interpolateColor(from: Color, to: Color, t: Double, space: InterpolationColorSpace): Color =
    when (space) {
        InterpolationColorSpace.RGB ->
            rgbArrayToColor(interpolateArray(from.toRgbArray(), to.toRgbArray(), t))

        InterpolationColorSpace.HCL -> {
            val f = rgbToHcl(from.toRgbArray())
            val to2 = rgbToHcl(to.toRgbArray())
            val hue0 = f[0]; val chroma0 = f[1]; val light0 = f[2]; val alphaF = f[3]
            val hue1 = to2[0]; val chroma1 = to2[1]; val light1 = to2[2]; val alphaT = to2[3]

            var hue: Double
            var chroma: Double? = null

            if (!hue0.isNaN() && !hue1.isNaN()) {
                var dh = hue1 - hue0
                if (hue1 > hue0 && dh > 180) {
                    dh -= 360
                } else if (hue1 < hue0 && hue0 - hue1 > 180) {
                    dh += 360
                }
                hue = hue0 + t * dh
            } else if (!hue0.isNaN()) {
                hue = hue0
                if (light1 == 1.0 || light1 == 0.0) chroma = chroma0
            } else if (!hue1.isNaN()) {
                hue = hue1
                if (light0 == 1.0 || light0 == 0.0) chroma = chroma1
            } else {
                hue = Double.NaN
            }

            rgbArrayToColor(
                hclToRgb(
                    doubleArrayOf(
                        hue,
                        chroma ?: interpolateNumber(chroma0, chroma1, t),
                        interpolateNumber(light0, light1, t),
                        interpolateNumber(alphaF, alphaT, t),
                    )
                )
            )
        }

        InterpolationColorSpace.LAB -> rgbArrayToColor(
            labToRgb(interpolateArray(rgbToLab(from.toRgbArray()), rgbToLab(to.toRgbArray()), t))
        )
    }
