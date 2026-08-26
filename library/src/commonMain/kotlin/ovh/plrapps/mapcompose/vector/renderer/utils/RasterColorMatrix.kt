package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.ColorMatrix
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The `raster-*` colour adjustments as a single colour matrix.
 *
 * Port of the RGB half of maplibre-gl-js `src/shaders/raster.fragment.glsl`, with the factors from
 * `src/render/draw_raster.ts`. Upstream runs, on un-premultiplied RGB:
 *
 * ```glsl
 * rgb = vec3(dot(rgb, u_spin_weights.xyz), dot(rgb, u_spin_weights.zxy), dot(rgb, u_spin_weights.yzx));
 * float average = (color.r + color.g + color.b) / 3.0;
 * rgb += (average - rgb) * u_saturation_factor;
 * rgb = (rgb - 0.5) * u_contrast_factor + 0.5;
 * gl_FragColor = vec4(mix(u_low_vec, u_high_vec, rgb) * color.a, color.a);
 * ```
 *
 * Every step is affine in RGB, so the four collapse into one 4x5 matrix -- applied in that order,
 * which means the composed linear part is `gain * (Saturation * Spin)`, not the other way round.
 *
 * `raster-opacity` is deliberately *not* folded in: upstream applies it as `color.a *= u_opacity`
 * before any of this, which is exactly `drawImage(alpha = opacity)`, and leaving it out is what lets
 * the identity case below return `null`.
 *
 * Returns `null` when every adjustment is at its spec default -- the overwhelmingly common case, and
 * worth detecting because it lets the painter skip the colour filter altogether.
 *
 * **The translation column is in 0..255.** [ColorMatrix] follows Android's convention, and the skiko
 * actual (`SkiaColorFilter.skiko.kt`) scales indices 4/9/14/19 by `1/255` on its way to
 * `SkColorMatrix`, so the same values are correct on every target. Both backends apply the matrix to
 * un-premultiplied colour, which is what the shader above assumes.
 */
internal fun rasterColorMatrix(
    hueRotate: Float,
    saturation: Float,
    contrast: Float,
    brightnessMin: Float,
    brightnessMax: Float,
): ColorMatrix? {
    val isIdentity = hueRotate == StyleSpecDefaults.RASTER_HUE_ROTATE.toFloat() &&
        saturation == StyleSpecDefaults.RASTER_SATURATION.toFloat() &&
        contrast == StyleSpecDefaults.RASTER_CONTRAST.toFloat() &&
        brightnessMin == StyleSpecDefaults.RASTER_BRIGHTNESS_MIN.toFloat() &&
        brightnessMax == StyleSpecDefaults.RASTER_BRIGHTNESS_MAX.toFloat()
    if (isIdentity) return null

    /* Spin, from the shader's swizzles: R' reads the weights as (w0, w1, w2), G' as (w2, w0, w1)
     * and B' as (w1, w2, w0). Row-major, `spin[out][in]`. */
    val w = spinWeights(hueRotate)
    val spin = arrayOf(
        floatArrayOf(w[0], w[1], w[2]),
        floatArrayOf(w[2], w[0], w[1]),
        floatArrayOf(w[1], w[2], w[0]),
    )

    /* Saturation mixes each channel towards the average of all three, so as a matrix it is
     * `1 - f + f/3` on the diagonal and `f/3` off it. */
    val f = saturationFactor(saturation)
    val diagonal = 1f - f + f / 3f
    val offDiagonal = f / 3f

    /* Contrast and brightness are both scale-then-translate, so they add one scalar gain and one
     * scalar offset shared by all three channels:
     *   contrast:   rgb = (rgb - 0.5) * cf + 0.5
     *   brightness: rgb = low + rgb * (high - low)
     * composed, gain = cf * (high - low) and offset = low + (high - low) * 0.5 * (1 - cf). */
    val cf = contrastFactor(contrast)
    val range = brightnessMax - brightnessMin
    val gain = cf * range
    val offset = brightnessMin + range * 0.5f * (1f - cf)

    // linear[out][in] = gain * sum over k of saturation[out][k] * spin[k][in]
    val linear = Array(3) { out ->
        FloatArray(3) { inp ->
            var sum = 0f
            for (k in 0..2) {
                val sat = if (out == k) diagonal else offDiagonal
                sum += sat * spin[k][inp]
            }
            sum * gain
        }
    }

    val translate = offset * 255f

    return ColorMatrix(
        floatArrayOf(
            linear[0][0], linear[0][1], linear[0][2], 0f, translate,
            linear[1][0], linear[1][1], linear[1][2], 0f, translate,
            linear[2][0], linear[2][1], linear[2][2], 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

/** The hue-rotation weights, a port of `spinWeights` in `src/render/draw_raster.ts`. */
internal fun spinWeights(angleDeg: Float): FloatArray {
    val angle = angleDeg * PI.toFloat() / 180f
    val s = sin(angle)
    val c = cos(angle)
    return floatArrayOf(
        (2f * c + 1f) / 3f,
        (-sqrt(3f) * s - c + 1f) / 3f,
        (sqrt(3f) * s - c + 1f) / 3f,
    )
}

/** Port of `contrastFactor` in `src/render/draw_raster.ts`. */
internal fun contrastFactor(contrast: Float): Float =
    if (contrast > 0f) 1f / (1f - contrast) else 1f + contrast

/** Port of `saturationFactor` in `src/render/draw_raster.ts`. */
internal fun saturationFactor(saturation: Float): Float =
    if (saturation > 0f) 1f - 1f / (1.001f - saturation) else -saturation
