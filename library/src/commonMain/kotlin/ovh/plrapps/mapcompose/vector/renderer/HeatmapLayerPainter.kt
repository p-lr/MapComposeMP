package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.renderer.utils.COLOR_RAMP_RESOLUTION
import ovh.plrapps.mapcompose.vector.renderer.utils.bilinearSample
import ovh.plrapps.mapcompose.vector.renderer.utils.kernelExtentInRadii
import ovh.plrapps.mapcompose.vector.renderer.utils.kernelValue
import ovh.plrapps.mapcompose.vector.renderer.utils.sampleColorRamp
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.HeatmapLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsHeatmapColor
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One MVT tile adjacent to the tile being rasterized, and which way it lies.
 *
 * [dx] and [dy] are in tiles, each `-1`, `0` or `1`, so a point of [tile] lands in the drawn tile's
 * canvas space at `x + dx * canvasSize`. Only [HeatmapLayerPainter] needs these; see the class KDoc
 * for why.
 */
class NeighbourTile(val tile: Tile, val dx: Int, val dy: Int)

/**
 * A point feature contributing to the density field, already in the drawn tile's canvas space.
 *
 * The coordinates may fall outside `0..canvasSize`: a point in a neighbouring tile still heats this
 * one if its kernel reaches in. [properties] is what the data-driven `heatmap-weight` and
 * `heatmap-radius` are evaluated against.
 */
class HeatmapPoint(val x: Double, val y: Double, val properties: EvalFeature?)

/**
 * Draws `heatmap` layers -- a kernel density estimate of point features, coloured by a ramp.
 *
 * Follows maplibre-gl-js `src/webgl/draw/draw_heatmap.ts`. Upstream runs two GPU passes: an
 * offscreen pass that sums a Gaussian kernel per point into a density buffer with additive
 * blending, then a pass that maps that buffer through the `heatmap-color` ramp. Both are ported
 * into `renderer/utils/HeatmapKernel.kt` and run here in one CPU loop.
 *
 * **How the tile seam is avoided.** Upstream's flat path accumulates the *whole viewport* into one
 * framebuffer with `StencilMode.disabled`, commented "Allow kernels to be drawn across boundaries,
 * so that large kernels are not clipped to tiles". A tile here is rasterized on its own, so instead
 * the caller ([TileRenderer]) hands over the points of the 8 neighbouring tiles as well
 * ([NeighbourTile]) -- the same border treatment
 * [ovh.plrapps.mapcompose.vector.data.DemData.backfillBorder] needs for hillshade. The kernel is
 * finite (see `kernelExtentInRadii`), so as long as a neighbour's points are included the result
 * inside the tile is what upstream's shared framebuffer would hold. Points are de-duplicated the
 * way upstream does it, in `CircleBucket.addFeature`: a point outside its own tile's `0..extent` is
 * dropped, so one that the MVT buffer duplicated into a neighbour is only ever counted once.
 *
 * This does not extend [BaseLayerPainter], because that contract paints one feature at a time and a
 * density field is by definition not separable per feature. The class is stateless, so
 * [TileRenderer] keeps one instance rather than one per layer.
 *
 * Known divergences:
 * - **A kernel wider than one tile is still clipped.** Only the 8 immediate neighbours are
 *   gathered, so a `heatmap-radius` beyond roughly `tileSize / kernelExtentInRadii` -- about 400 px
 *   at the default weight and intensity on 512 px tiles -- loses the contribution of points two
 *   tiles away.
 * - **`heatmap-opacity` does not animate.** It is folded into the ramp when the tile is
 *   rasterized; there is no frame loop to re-evaluate it against, the same reason
 *   `raster-fade-duration` is inert in [RasterLayerPainter].
 * - **The result is resampled once more than upstream**, when the tile bitmap is drawn to screen.
 *   Shared with [RasterLayerPainter] and [HillshadeLayerPainter], and the price of drawing into the
 *   tile bitmap so the layer keeps its place in style order rather than becoming an overlay.
 */
class HeatmapLayerPainter {

    suspend fun paint(
        canvas: DrawScope,
        style: HeatmapLayer,
        points: List<HeatmapPoint>,
        canvasSize: Int,
        actualZoom: Double,
    ) {
        if (canvasSize <= 0) return
        val paint = style.paint

        /* Upstream returns before it even binds the offscreen framebuffer at zero opacity. */
        val opacity = (paint?.heatmapOpacity.processAsDouble(zoom = actualZoom)
            ?: StyleSpecDefaults.HEATMAP_OPACITY).coerceIn(0.0, 1.0)
        if (opacity <= 0.0) return

        val intensity = paint?.heatmapIntensity.processAsDouble(zoom = actualZoom)
            ?: StyleSpecDefaults.HEATMAP_INTENSITY

        /* Upstream's density framebuffer is a quarter of the screen in each axis -- "Use a 4x
         * downscaled screen texture for better performance" -- and is magnified back with LINEAR
         * filtering. The same ratio against the tile bitmap keeps the splatting loop cheap. */
        val dim = max(1, canvasSize / DENSITY_DOWNSCALE)
        val cell = canvasSize.toDouble() / dim
        val field = FloatArray(dim * dim)
        val density = canvas.density

        var anyContribution = false
        /* The cells any kernel actually reached. Outside them the field stays zero, so the resample
         * below would only write the ramp's density-0 colour over and over. */
        var touchedMinCol = dim
        var touchedMaxCol = -1
        var touchedMinRow = dim
        var touchedMaxRow = -1

        for (point in points) {
            val weight = paint?.heatmapWeight.processAsDouble(point.properties, actualZoom)
                ?: StyleSpecDefaults.HEATMAP_WEIGHT
            /* `heatmap-radius` is in screen pixels, as `circle-radius` is. */
            val radius = (paint?.heatmapRadius.processAsDouble(point.properties, actualZoom)
                ?: StyleSpecDefaults.HEATMAP_RADIUS) * density
            if (radius <= 0.0) continue

            val extentInRadii = kernelExtentInRadii(weight, intensity)
            if (extentInRadii <= 0.0) continue
            val reach = extentInRadii * radius

            val minCol = floor((point.x - reach) / cell - 0.5).toInt().coerceAtLeast(0)
            val maxCol = ceil((point.x + reach) / cell - 0.5).toInt().coerceAtMost(dim - 1)
            val minRow = floor((point.y - reach) / cell - 0.5).toInt().coerceAtLeast(0)
            val maxRow = ceil((point.y + reach) / cell - 0.5).toInt().coerceAtMost(dim - 1)
            if (minCol > maxCol || minRow > maxRow) continue

            for (row in minRow..maxRow) {
                val dy = (row + 0.5) * cell - point.y
                for (col in minCol..maxCol) {
                    val dx = (col + 0.5) * cell - point.x
                    val distanceInRadii = sqrt(dx * dx + dy * dy) / radius
                    if (distanceInRadii > extentInRadii) continue
                    field[row * dim + col] += kernelValue(weight, intensity, distanceInRadii).toFloat()
                    anyContribution = true
                    if (col < touchedMinCol) touchedMinCol = col
                    if (col > touchedMaxCol) touchedMaxCol = col
                    if (row < touchedMinRow) touchedMinRow = row
                    if (row > touchedMaxRow) touchedMaxRow = row
                }
            }
        }

        val ramp = buildColorRamp(style, opacity)
        /* Upstream draws its full-screen quad whether or not any tile had a bucket, so a ramp that
         * is opaque at density 0 tints everything. That is honoured, but a tile with no heat and
         * the usual transparent-at-0 ramp is skipped rather than filled with transparent pixels. */
        if (!anyContribution && ramp[0] ushr 24 == 0) return

        /* Everywhere the field is zero, `bilinearSample` returns 0 and `sampleColorRamp` returns
         * `ramp[0]` exactly, so the whole tile outside the touched cells is one flat colour. The
         * usual ramp is transparent there and a zeroed array already says so; a ramp that is opaque
         * at density 0 still tints the tile, as upstream's full-screen quad does. */
        val background = ramp[0]
        val pixels = if (background == 0) {
            IntArray(canvasSize * canvasSize)
        } else {
            IntArray(canvasSize * canvasSize) { background }
        }

        /* Two cells of margin covers `bilinearSample`'s neighbouring tap in either direction, with
         * room to spare -- one cell is DENSITY_DOWNSCALE pixels. */
        val xFrom = floor((touchedMinCol - 2) * cell).toInt().coerceAtLeast(0)
        val xTo = ceil((touchedMaxCol + 3) * cell).toInt().coerceAtMost(canvasSize - 1)
        val yFrom = floor((touchedMinRow - 2) * cell).toInt().coerceAtLeast(0)
        val yTo = ceil((touchedMaxRow + 3) * cell).toInt().coerceAtMost(canvasSize - 1)

        for (y in yFrom..yTo) {
            val sampleY = (y + 0.5) / cell - 0.5
            val rowOffset = y * canvasSize
            for (x in xFrom..xTo) {
                val sampleX = (x + 0.5) / cell - 0.5
                val t = bilinearSample(field, dim, sampleX, sampleY)
                pixels[rowOffset + x] = sampleColorRamp(ramp, t)
            }
        }

        canvas.drawImage(
            image = imageBitmapFromArgb(pixels, canvasSize, canvasSize),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(canvasSize, canvasSize),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(canvasSize, canvasSize),
            filterQuality = FilterQuality.Low,
        )
    }

    /**
     * The layer's `heatmap-color` sampled into upstream's 256-entry ramp texture, as straight-alpha
     * ARGB.
     *
     * `heatmap-opacity` is folded in here rather than applied to the drawn image: upstream's
     * `fragColor = color * u_opacity` scales a premultiplied colour, which on a straight-alpha
     * colour is exactly scaling the alpha.
     */
    private fun buildColorRamp(style: HeatmapLayer, opacity: Double): IntArray {
        val declared = style.paint?.heatmapColor
        val ramp = IntArray(COLOR_RAMP_RESOLUTION)
        for (i in ramp.indices) {
            val t = i.toDouble() / (COLOR_RAMP_RESOLUTION - 1)
            val color = declared.processAsHeatmapColor(t)
                ?: DEFAULT_COLOR_RAMP.processAsHeatmapColor(t)
                ?: Color.Transparent
            ramp[i] = color.copy(alpha = (color.alpha * opacity).toFloat().coerceIn(0f, 1f)).toArgb()
        }
        return ramp
    }

    private companion object {
        /** Upstream's `painter.width / 4`, applied to the tile bitmap instead of the screen. */
        const val DENSITY_DOWNSCALE = 4

        /**
         * The spec default for `heatmap-color`, compiled once through the ordinary property
         * serializer so it behaves identically to a style that spells the same ramp out.
         */
        val DEFAULT_COLOR_RAMP: ExpressionOrValue<Color> by lazy {
            json.decodeFromString(ExpressionOrValueColorSerializer, StyleSpecDefaults.HEATMAP_COLOR)
        }
    }
}
