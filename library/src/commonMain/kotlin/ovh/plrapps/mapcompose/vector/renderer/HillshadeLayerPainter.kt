package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.renderer.utils.shadePixel
import ovh.plrapps.mapcompose.vector.renderer.utils.slopeDivisor
import ovh.plrapps.mapcompose.vector.renderer.utils.sobelDeriv
import ovh.plrapps.mapcompose.vector.renderer.utils.tileLatRange
import ovh.plrapps.mapcompose.vector.spec.style.HillshadeLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import kotlin.math.PI

/**
 * The elevation tile of a `raster-dem` source, and which part of it one map tile needs.
 *
 * The [DemData] always covers the whole fetched tile -- border ring included -- and [ref] says which
 * `span x span` sub-square of it the map tile is, exactly as [RasterTileImage] does for an image
 * source. Cropping at read time rather than at decode time is what lets several overzoomed map tiles
 * share one decoded DEM, and it keeps the border ring reachable from every sub-square.
 */
class DemTile(val dem: DemData, val ref: TileRef)

/**
 * Draws `hillshade` layers -- terrain relief lit from one direction.
 *
 * Follows maplibre-gl-js `src/render/draw_hillshade.ts`. Upstream runs two GPU passes: an offscreen
 * *prepare* pass that turns the DEM into a slope raster, then a *render* pass that lights it. Both
 * are ported into [ovh.plrapps.mapcompose.vector.renderer.utils.sobelDeriv] and
 * [ovh.plrapps.mapcompose.vector.renderer.utils.shadePixel] and run here in one CPU loop, at the
 * DEM's own resolution, before the result is scaled onto the tile -- which is what upstream's
 * `LINEAR`-filtered prepare texture amounts to.
 *
 * This does not extend [BaseLayerPainter], for the same reason [RasterLayerPainter] does not: that
 * contract is shaped around a feature, an `extent` and a `source-layer`, and a source-based layer
 * has none of them. The class is stateless, so [TileRenderer] keeps one instance rather than one
 * per layer.
 *
 * Known divergences:
 * - **`hillshade-illumination-anchor` is inert; the light is always anchored to the map.** Upstream
 *   subtracts the map bearing from the azimuth when the anchor is `viewport`, but a tile here is
 *   rasterized without knowing the bearing, and would not be re-rasterized when the map rotates --
 *   the same reason a `viewport`-anchored `*-translate` is not counter-rotated. Note this is the
 *   spec *default*, so a style that says nothing gets map-anchored light.
 * - **Lighting is evaluated per DEM sample and the colours are interpolated**, where upstream
 *   interpolates the slope and lights each screen pixel. The difference shows only where a DEM
 *   sample covers several screen pixels.
 * - **The result is resampled twice**, once into the tile bitmap and again when that bitmap is
 *   drawn, and a source's `bounds` is not honoured -- both shared with [RasterLayerPainter], and
 *   both the price of drawing into the tile bitmap so the layer keeps its place in style order.
 */
class HillshadeLayerPainter {

    suspend fun paint(
        canvas: DrawScope,
        style: HillshadeLayer,
        demTile: DemTile,
        canvasSize: Int,
        tileZ: Int,
        tileY: Int,
        actualZoom: Double,
    ) {
        val paint = style.paint

        val exaggeration = paint?.hillshadeExaggeration.processAsDouble(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_EXAGGERATION
        /* Upstream's `hasOffscreenPass()` drops the layer entirely at zero intensity, which is also
         * the only value where the shading below would be uniformly transparent anyway. */
        if (exaggeration == 0.0) return

        val illuminationDirection = paint?.hillshadeIlluminationDirection.processAsDouble(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_ILLUMINATION_DIRECTION
        val shadow = paint?.hillshadeShadowColor.processAsColor(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_SHADOW_COLOR
        val highlight = paint?.hillshadeHighlightColor.processAsColor(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_HIGHLIGHT_COLOR
        val accent = paint?.hillshadeAccentColor.processAsColor(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_ACCENT_COLOR

        val dem = demTile.dem
        val ref = demTile.ref
        val span = ref.span.coerceAtLeast(1)
        /* The sub-square the map tile covers. It is a whole tile in the common case; when the source
         * is overzoomed it is one square of the ancestor, which magnifies rather than resamples --
         * there is no more elevation data to be had. */
        val size = dem.dim / span
        if (size <= 0) return
        val originX = ref.subX * size
        val originY = ref.subY * size

        val divisor = slopeDivisor(tileZoom = actualZoom, demZoom = ref.z, dim = dem.dim)
        val azimuthRad = illuminationDirection * PI / 180.0
        val (latTop, latBottom) = tileLatRange(z = tileZ, y = tileY)

        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            /* Upstream's `u_latrange` interpolation, over the map tile rather than over the DEM. */
            val t = (row + 0.5) / size
            val latitude = latTop + (latBottom - latTop) * t

            for (col in 0 until size) {
                val (derivX, derivY) = sobelDeriv(
                    dem = dem,
                    x = originX + col,
                    y = originY + row,
                    divisor = divisor,
                )
                val color = if (derivX == 0.0 && derivY == 0.0) {
                    /* Flat ground: sin(0) and 1 - cos(0) are both zero, so the shader's output is
                     * fully transparent. Short-circuited because most of a DEM tile is flat. */
                    Color.Transparent
                } else {
                    shadePixel(
                        derivX = derivX,
                        derivY = derivY,
                        latitude = latitude,
                        intensity = exaggeration,
                        azimuthRad = azimuthRad,
                        shadow = shadow,
                        highlight = highlight,
                        accent = accent,
                    )
                }
                pixels[row * size + col] = color.toArgb()
            }
        }

        canvas.drawImage(
            image = imageBitmapFromArgb(pixels, size, size),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(size, size),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(canvasSize, canvasSize),
            filterQuality = FilterQuality.Low,
        )
    }
}
