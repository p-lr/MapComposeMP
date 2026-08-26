package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The heatmap kernel-density maths, ported from maplibre-gl-js.
 *
 * Three shader steps live here, all pure:
 * - [kernelValue] is `src/shaders/glsl/heatmap.fragment.glsl`, the Gaussian one point contributes
 *   at a given distance.
 * - [kernelExtentInRadii] is the `S` the vertex shader solves for, which is how far out that
 *   Gaussian is still worth accumulating.
 * - [sampleColorRamp] and [bilinearSample] are the two texture reads
 *   `src/shaders/glsl/heatmap_texture.fragment.glsl` does: the `LINEAR`-magnified density texture,
 *   and the `LINEAR`, `CLAMP_TO_EDGE` colour ramp.
 *
 * They are kept out of [ovh.plrapps.mapcompose.vector.renderer.HeatmapLayerPainter] so that
 * `commonTest` can assert on them directly, the way `HillshadeShading`'s are -- the
 * painter itself needs an `ImageBitmap`, which only `skiaTest` can allocate.
 */

/** Gaussian kernel coefficient, `1 / sqrt(2 * PI)`. Upstream's `GAUSS_COEF`. */
const val GAUSS_COEF = 0.3989422804014327

/**
 * Effective "0" in the kernel density texture, upstream's `ZERO`.
 *
 * Its comment: "this empirically chosen number minimizes artifacts on overlapping kernels for
 * typical heatmap cases (assuming clustered source)". It is one sixteenth of an 8-bit step, which
 * is what makes it a sensible truncation point for a texture that used to be `RGBA8`.
 */
const val KERNEL_ZERO = 1.0 / 255.0 / 16.0

/** Upstream's `-0.5 * 3.0 * 3.0`, the exponent scale that puts three sigma at one radius. */
private const val EXPONENT_SCALE = -0.5 * 3.0 * 3.0

/** The number of entries in the colour ramp, matching upstream's 256 x 1 ramp texture. */
const val COLOR_RAMP_RESOLUTION = 256

/**
 * How far out, in radii, one point's kernel is still above [KERNEL_ZERO].
 *
 * This is the `S` the vertex shader sizes its quad with, solving
 * `weight * intensity * GAUSS_COEF * exp(-0.5 * 3^2 * S^2) == ZERO`. A kernel whose peak is already
 * below `ZERO` -- a zero or negative [weight] or [intensity] included -- has no extent at all;
 * upstream gets the same outcome from `log` of a non-positive number collapsing the quad.
 */
fun kernelExtentInRadii(weight: Double, intensity: Double): Double {
    val peak = weight * intensity * GAUSS_COEF
    if (peak <= KERNEL_ZERO) return 0.0
    return sqrt(-2.0 * ln(KERNEL_ZERO / peak)) / 3.0
}

/**
 * One point's contribution to the density field at [distanceInRadii] from it, where 1.0 is one
 * `heatmap-radius` away.
 */
fun kernelValue(weight: Double, intensity: Double, distanceInRadii: Double): Double =
    weight * intensity * GAUSS_COEF * exp(EXPONENT_SCALE * distanceInRadii * distanceInRadii)

/**
 * Reads the density field as GL's `LINEAR`, `CLAMP_TO_EDGE` magnification does.
 *
 * [x] and [y] are in cell units, with `0.0` the centre of cell 0 -- so a sample that lands exactly
 * on a cell centre returns that cell untouched, and one outside the field clamps to its edge.
 */
fun bilinearSample(field: FloatArray, dim: Int, x: Double, y: Double): Double {
    if (dim <= 0 || field.isEmpty()) return 0.0
    val last = dim - 1
    val cx = x.coerceIn(0.0, last.toDouble())
    val cy = y.coerceIn(0.0, last.toDouble())
    val x0 = floor(cx).toInt()
    val y0 = floor(cy).toInt()
    val x1 = min(x0 + 1, last)
    val y1 = min(y0 + 1, last)
    val tx = cx - x0
    val ty = cy - y0

    val top = field[y0 * dim + x0] + (field[y0 * dim + x1] - field[y0 * dim + x0]) * tx
    val bottom = field[y1 * dim + x0] + (field[y1 * dim + x1] - field[y1 * dim + x0]) * tx
    return top + (bottom - top) * ty
}

/**
 * Reads a ramp of straight-alpha ARGB entries at [t], as GL's `LINEAR`, `CLAMP_TO_EDGE` ramp
 * texture does.
 *
 * The channels are interpolated straight rather than premultiplied because upstream's
 * `renderColorRamp` explicitly unpremultiplies before uploading -- "the colors are being
 * unpremultiplied because Color uses premultiplied values, and the Texture class expects
 * unpremultiplied ones".
 */
fun sampleColorRamp(ramp: IntArray, t: Double): Int {
    if (ramp.isEmpty()) return 0
    val last = ramp.size - 1
    val pos = t.coerceIn(0.0, 1.0) * last
    val i = floor(pos).toInt().coerceIn(0, last)
    val j = min(i + 1, last)
    val f = pos - i
    if (i == j || f <= 0.0) return ramp[i]

    val a = ramp[i]
    val b = ramp[j]
    var out = 0
    for (shift in CHANNEL_SHIFTS) {
        val ca = (a ushr shift) and 0xFF
        val cb = (b ushr shift) and 0xFF
        val c = (ca + (cb - ca) * f).roundToInt().coerceIn(0, 255)
        out = out or (c shl shift)
    }
    return out
}

private val CHANNEL_SHIFTS = intArrayOf(24, 16, 8, 0)
