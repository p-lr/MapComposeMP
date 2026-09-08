package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.renderer.utils.HillshadeMethod
import ovh.plrapps.mapcompose.vector.renderer.utils.illuminationSources
import ovh.plrapps.mapcompose.vector.renderer.utils.shadePixel
import ovh.plrapps.mapcompose.vector.renderer.utils.slopeDivisor
import ovh.plrapps.mapcompose.vector.renderer.utils.sobelDeriv
import ovh.plrapps.mapcompose.vector.renderer.utils.tileLatRange
import ovh.plrapps.mapcompose.vector.spec.style.HillshadeLayer
import ovh.plrapps.mapcompose.vector.spec.style.RESAMPLING_NEAREST
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColorArray
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsNumberArray
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

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
 * All five of upstream's `hillshade-method` algorithms are implemented, and a style may declare
 * several illumination sources -- the properties are `numberArray` / `colorArray` in the spec, and
 * `multidirectional` averages one shading pass per source.
 *
 * Known divergences:
 * - **`hillshade-illumination-anchor` is inert; the light is always anchored to the map.** Upstream
 *   *adds* the map bearing to every azimuth when the anchor is `viewport`, but a tile here is
 *   rasterized without knowing the bearing, and would not be re-rasterized when the map rotates --
 *   the same reason a `viewport`-anchored `*-translate` is not counter-rotated. Note this is the
 *   spec *default*, so a style that says nothing gets map-anchored light. It applies to every
 *   source of a `multidirectional` layer alike.
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

        val exaggeration = paint.hillshadeExaggeration.processAsDouble(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_EXAGGERATION
        /* Upstream's `hasOffscreenPass()` drops the layer entirely at zero intensity, which is also
         * the only value where the shading below would be uniformly transparent anyway. */
        if (exaggeration == 0.0) return

        val method = HillshadeMethod.ofOrDefault(
            paint.hillshadeMethod.processAsString(zoom = actualZoom)
        )
        val sources = illuminationSources(
            directionsDeg = paint.hillshadeIlluminationDirection
                .processAsNumberArray(zoom = actualZoom).orEmpty(),
            altitudesDeg = paint.hillshadeIlluminationAltitude
                .processAsNumberArray(zoom = actualZoom).orEmpty(),
            shadows = paint.hillshadeShadowColor.processAsColorArray(zoom = actualZoom).orEmpty(),
            highlights = paint.hillshadeHighlightColor
                .processAsColorArray(zoom = actualZoom).orEmpty(),
            fallbackDirectionDeg = StyleSpecDefaults.HILLSHADE_ILLUMINATION_DIRECTION,
            fallbackAltitudeDeg = StyleSpecDefaults.HILLSHADE_ILLUMINATION_ALTITUDE,
            fallbackShadow = StyleSpecDefaults.HILLSHADE_SHADOW_COLOR,
            fallbackHighlight = StyleSpecDefaults.HILLSHADE_HIGHLIGHT_COLOR,
        )
        val accent = paint.hillshadeAccentColor.processAsColor(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_ACCENT_COLOR
        val resampling = paint.resampling.processAsString(zoom = actualZoom)
            ?: StyleSpecDefaults.HILLSHADE_RESAMPLING

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
        val (latTop, latBottom) = tileLatRange(z = tileZ, y = tileY)

        /* Flat ground short-circuited, because most of a DEM tile is flat. It is *not* always
         * transparent: `standard`, `igor` and `combined` all reach zero there, but `basic` and
         * `multidirectional` light a flat surface by the cosine of the light's altitude, which at
         * the default 45 degrees is a uniform 41% highlight. So the colour is computed once rather
         * than assumed -- a zero derivative survives any latitude correction, so one value covers
         * the whole tile. */
        val flatColor = shadePixel(
            derivX = 0.0,
            derivY = 0.0,
            latitude = latTop,
            exaggeration = exaggeration,
            method = method,
            sources = sources,
            accent = accent,
        ).toArgb()

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
                pixels[row * size + col] = if (derivX == 0.0 && derivY == 0.0) {
                    flatColor
                } else {
                    shadePixel(
                        derivX = derivX,
                        derivY = derivY,
                        latitude = latitude,
                        exaggeration = exaggeration,
                        method = method,
                        sources = sources,
                        accent = accent,
                    ).toArgb()
                }
            }
        }

        canvas.drawImage(
            image = imageBitmapFromArgb(pixels, size, size),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(size, size),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(canvasSize, canvasSize),
            filterQuality = if (resampling == RESAMPLING_NEAREST) {
                FilterQuality.None
            } else {
                FilterQuality.Low
            },
        )
    }
}
