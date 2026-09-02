package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.math.min

/**
 * The line ribbon's width and alpha falloff, ported from maplibre-gl-js.
 *
 * `src/shaders/glsl/line.vertex.glsl` sizes the ribbon:
 *
 * ```glsl
 * float ANTIALIASING = 1.0 / u_device_pixel_ratio / 2.0;
 * gapwidth = gapwidth / 2.0;
 * float halfwidth = width / 2.0;
 * float inset  = gapwidth + (gapwidth > 0.0 ? ANTIALIASING : 0.0);
 * float outset = gapwidth + halfwidth * (gapwidth > 0.0 ? 2.0 : 1.0) + (halfwidth == 0.0 ? 0.0 : ANTIALIASING);
 * v_width2 = vec2(outset, inset);
 * ```
 *
 * and `src/shaders/glsl/line.fragment.glsl` shades it:
 *
 * ```glsl
 * float dist = length(v_normal) * v_width2.s;
 * float blur2 = (blur + 1.0 / u_device_pixel_ratio) * v_gamma_scale;
 * float alpha = clamp(min(dist - (v_width2.t - blur2), v_width2.s - dist) / blur2, 0.0, 1.0);
 * fragColor = color * (alpha * opacity);
 * ```
 *
 * Two things follow, and they are what let a CPU triangle mesh reproduce the GPU pass exactly
 * rather than approximate it:
 *
 * - `alpha` is **piecewise linear in `dist`**, so vertices placed at its breakpoints
 *   ([alphaRings]) and Gouraud-interpolated between reproduce it with no error at all. That holds
 *   for `blur = 0` too: `blur2` is never zero, and the `1 / dpr` term is upstream's own one-pixel
 *   antialias feather. It is why the mesh is drawn with antialiasing *off* -- the feather is the
 *   geometry, and Skia would otherwise antialias each triangle on its own and leave seams.
 * - `outset` carries no `blur` term. `line-blur` softens the line *within* its own width and never
 *   widens it, so a blur larger than the line makes it faint rather than making it glow.
 *
 * **Divergence:** `v_gamma_scale` is the perspective correction the vertex shader derives from the
 * projected extrude length. There is no camera pitch here, so it is 1 and is not modelled -- the
 * same reason `circle-pitch-scale` is inert.
 *
 * Kept pure and out of [ovh.plrapps.mapcompose.vector.renderer.LineLayerPainter] so that
 * `commonTest` can assert on them directly, as `HillshadeShading` and `HeatmapKernel` are.
 */

/** Upstream's `ANTIALIASING`: the half-pixel the ribbon is grown by so it has room to feather. */
internal fun antialiasing(devicePixelRatio: Float): Float = 1f / devicePixelRatio / 2f

/**
 * The inner edge of the ribbon, upstream's `inset` and `v_width2.t`.
 *
 * [gapWidth] is the style's `line-gap-width`; the shader halves it. A line without a gap has an
 * inset of zero, and the ribbon is solid to its centre.
 */
internal fun lineInset(gapWidth: Float, devicePixelRatio: Float): Float {
    val halfGap = gapWidth / 2f
    return halfGap + if (halfGap > 0f) antialiasing(devicePixelRatio) else 0f
}

/**
 * The outer edge of the ribbon, upstream's `outset` and `v_width2.s`.
 *
 * [width] is the style's `line-width`; the shader halves it. With a gap the styled width applies to
 * each side, hence upstream's `* 2.0`.
 */
internal fun lineOutset(gapWidth: Float, width: Float, devicePixelRatio: Float): Float {
    val halfGap = gapWidth / 2f
    val halfWidth = width / 2f
    return halfGap +
        halfWidth * (if (halfGap > 0f) 2f else 1f) +
        (if (halfWidth == 0f) 0f else antialiasing(devicePixelRatio))
}

/** Upstream's `blur2`, the distance the edge fades over. Always positive, so [lineAlpha] is safe. */
internal fun blur2(blur: Float, devicePixelRatio: Float): Float = blur + 1f / devicePixelRatio

/** The fragment shader's `alpha` at [dist] pixels from the centre of the line. */
internal fun lineAlpha(dist: Float, outset: Float, inset: Float, blur2: Float): Float {
    val fadeIn = dist - (inset - blur2)
    val fadeOut = outset - dist
    return (min(fadeIn, fadeOut) / blur2).coerceIn(0f, 1f)
}

/**
 * Where the mesh puts its vertices across the line's normal, as signed fractions of [outset].
 *
 * Ascending from -1 to 1, always containing -1, 0 and 1. These are the breakpoints of [lineAlpha]:
 * the two points where each of its linear branches enters or leaves the clamp, and the crossover
 * where `min` switches branch. A ribbon whose rings sit here and whose vertex alphas come from
 * [lineAlpha] reproduces the shader exactly between them.
 */
internal fun alphaRings(outset: Float, inset: Float, blur2: Float): FloatArray {
    if (outset <= 0f) return floatArrayOf(-1f, 0f, 1f)

    val candidates = floatArrayOf(
        0f,
        inset - blur2,
        inset,
        outset - blur2,
        outset,
        (inset - blur2 + outset) / 2f,
    )

    val distances = ArrayList<Float>(candidates.size)
    for (candidate in candidates) {
        val clamped = candidate.coerceIn(0f, outset)
        if (distances.none { abs(it - clamped) < RING_EPSILON * outset }) distances.add(clamped)
    }
    distances.sort()

    // A candidate that is not actually a slope change adds vertices without adding accuracy: the
    // common case, a sharp line, keeps only the centre, the start of the feather and the edge.
    var index = 1
    while (index < distances.size - 1) {
        val before = distances[index - 1]
        val here = distances[index]
        val after = distances[index + 1]
        val alphaBefore = lineAlpha(before, outset, inset, blur2)
        val alphaAfter = lineAlpha(after, outset, inset, blur2)
        val interpolated = alphaBefore + (alphaAfter - alphaBefore) * ((here - before) / (after - before))
        if (abs(interpolated - lineAlpha(here, outset, inset, blur2)) < RING_EPSILON) {
            distances.removeAt(index)
        } else {
            index++
        }
    }

    val rings = FloatArray(distances.size * 2 - 1)
    var i = 0
    for (position in distances.indices.reversed()) rings[i++] = -distances[position] / outset
    for (position in 1 until distances.size) rings[i++] = distances[position] / outset
    return rings
}

/**
 * Two rings closer together than this fraction of the ribbon's half-width are treated as one.
 *
 * Without it a line whose blur happens to land on a breakpoint would emit degenerate triangles --
 * harmless to draw, but they double the mesh for nothing.
 */
private const val RING_EPSILON = 1e-4f
