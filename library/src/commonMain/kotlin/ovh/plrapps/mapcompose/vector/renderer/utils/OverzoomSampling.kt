package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Which samples of an overzoomed source raster one map tile needs, and where they land on it.
 *
 * Past a source's `maxzoom` the ancestor tile covers `span x span` map tiles, so one map tile is a
 * `dim / span` wide window of the ancestor's samples -- and that width is **fractional** as soon as
 * `span > dim`. Upstream never faces the question: `draw_raster.ts`, `draw_hillshade.ts` and
 * `draw_color_relief.ts` all draw the source tile's own quad and let the GPU sample the texture at
 * fractional coordinates. A tile here is rasterized into its own bitmap instead, so the window has
 * to be resolved on the CPU -- and resolving it by integer division is what made a 256-sample source
 * disappear nine levels above its `maxzoom`, the quotient having reached zero.
 *
 * [first]..[last] are inclusive sample indices, always non-empty and always inside `0 until dim`.
 * Sample `first + k` is drawn with its centre at `origin + (k + 0.5) * scale` device pixels, which
 * places the window itself over `0..canvasSize` however small or fractional it is.
 *
 * The range covers the window plus the half-sample that bilinear filtering reads on each side, so
 * two sub-squares of one ancestor share their boundary samples and magnify continuously -- an
 * integer crop clamped the filter at the sub-square's edge and seamed there. It is clamped to the
 * tile's own samples: [ovh.plrapps.mapcompose.vector.data.DemData] carries a border ring, but
 * [sobelDeriv] cannot shade it (it would read one sample further still), so the tile's outer edge
 * keeps the clamp it always had.
 */
class SampleWindow(
    val first: Int,
    val last: Int,
    val origin: Float,
    val scale: Float,
    private val canvasSize: Int,
) {
    val count: Int get() = last - first + 1

    /**
     * Where the `k`-th sample of this window sits across the map tile, as a fraction of it.
     *
     * Slightly outside `0..1` for the half-sample margin at either end, which callers that read it
     * as a position on the tile -- hillshade's latitude interpolation -- clamp.
     */
    fun tileFractionOf(k: Int): Double = (origin + (k + 0.5) * scale) / canvasSize
}

/**
 * The [SampleWindow] of the [sub]-th of `span` map tiles along one axis of a `dim`-sample source
 * tile, drawn over [canvasSize] device pixels.
 *
 * At `span == 1` this is the whole source: `first = 0`, `last = dim - 1`, `origin = 0`, and
 * `scale = canvasSize / dim`.
 */
fun sampleWindow(dim: Int, sub: Int, span: Int, canvasSize: Int): SampleWindow {
    require(dim > 0) { "a source tile has no samples: $dim" }
    val spanned = span.coerceAtLeast(1)
    val window = dim.toDouble() / spanned
    val originF = sub * window
    val scale = canvasSize / window

    /* A destination pixel at tile fraction u reads the source at `originF + u * window`, and
     * bilinear filtering there needs the samples on either side of that coordinate -- hence the
     * half-sample either way before rounding outwards. */
    val first = floor(originF - 0.5).toInt().coerceIn(0, dim - 1)
    val last = ceil(originF + window - 0.5).toInt().coerceIn(first, dim - 1)

    return SampleWindow(
        first = first,
        last = last,
        origin = ((first - originF) * scale).toFloat(),
        scale = scale.toFloat(),
        canvasSize = canvasSize,
    )
}
