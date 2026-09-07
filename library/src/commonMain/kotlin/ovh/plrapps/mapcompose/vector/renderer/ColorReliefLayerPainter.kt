package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.renderer.utils.colorReliefRamp
import ovh.plrapps.mapcompose.vector.spec.style.ColorReliefLayer
import ovh.plrapps.mapcompose.vector.spec.style.RESAMPLING_NEAREST
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

/**
 * Draws `color-relief` layers -- a DEM coloured by elevation.
 *
 * Follows maplibre-gl-js `src/webgl/draw/draw_color_relief.ts` and `color_relief.fragment.glsl`.
 * Upstream uploads the DEM as one texture and the `color-relief-color` ramp as two more, and the
 * fragment shader unpacks an elevation, binary-searches the ramp and blends; the DEM is already
 * unpacked into metres at decode here (`data/DemData.kt`), and the ramp lookup is
 * [ovh.plrapps.mapcompose.vector.renderer.utils.ColorReliefRamp.colorAt], so this is one CPU loop
 * over the DEM's own samples, scaled onto the tile exactly as [HillshadeLayerPainter] scales its
 * shading.
 *
 * This does not extend [BaseLayerPainter], for the reason [HillshadeLayerPainter] and
 * [RasterLayerPainter] do not: that contract is shaped around a feature, an `extent` and a
 * `source-layer`, and a source-based layer has none of them. The class is stateless, so
 * [TileRenderer] keeps one instance rather than one per layer.
 *
 * Known divergences:
 * - **Only a top-level `interpolate` over `["elevation"]` produces a ramp**, which is upstream's own
 *   rule and is surprising enough to be worth restating -- a `step`, a `case` or a plain colour
 *   draws nothing. See [ovh.plrapps.mapcompose.vector.renderer.utils.colorReliefRamp].
 * - **The elevation stops are exact metres.** Upstream packs each one back into the DEM's encoding
 *   (`packDEMData`) so the shader can compare it against an unpacked sample, which quantizes every
 *   stop to the encoding's step; nothing here needs the round trip.
 * - **`resampling` filters the resulting colours, not the elevations.** Upstream's `textureFilter`
 *   is applied to the DEM texture, so a magnified tile interpolates *elevations* and then colours
 *   them; the ramp is evaluated per DEM sample here and the small bitmap is filtered onto the tile.
 *   The same divergence [HillshadeLayerPainter] has, with the same scope: it shows only where one
 *   DEM sample covers several screen pixels.
 * - **The result is resampled twice**, once into the tile bitmap and again when that bitmap is
 *   drawn, and a source's `bounds` is not honoured -- both shared with [RasterLayerPainter] and
 *   [HillshadeLayerPainter], and both the price of drawing into the tile bitmap so the layer keeps
 *   its place in style order.
 * - **The DEM's border ring is backfilled from 8 neighbouring tiles anyway.** A colour ramp reads
 *   one sample at a time and needs no neighbourhood, but `VectorRasterizer.buildDem` is shared with
 *   `hillshade`, which does; the neighbours are tiles the map draws regardless, so a second
 *   border-less DEM variant would buy little.
 */
class ColorReliefLayerPainter {

    suspend fun paint(
        canvas: DrawScope,
        style: ColorReliefLayer,
        demTile: DemTile,
        canvasSize: Int,
        actualZoom: Double,
    ) {
        val paint = style.paint

        /* Nothing to draw at all when `color-relief-color` is absent or is not an interpolate: that
         * is upstream's one-stop transparent ramp, without the pass. */
        val ramp = colorReliefRamp(paint.colorReliefColor) ?: return

        val opacity = paint.colorReliefOpacity.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.COLOR_RELIEF_OPACITY.toFloat()
        if (opacity <= 0f) return

        val resampling = paint.resampling.processAsString(zoom = actualZoom)
            ?: StyleSpecDefaults.COLOR_RELIEF_RESAMPLING

        val dem = demTile.dem
        val ref = demTile.ref
        val span = ref.span.coerceAtLeast(1)
        /* The sub-square the map tile covers, as in [HillshadeLayerPainter]: the whole tile in the
         * common case, one square of the ancestor when the source is overzoomed -- which magnifies
         * rather than resamples, there being no more elevation data to be had. */
        val size = dem.dim / span
        if (size <= 0) return
        val originX = ref.subX * size
        val originY = ref.subY * size

        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            for (col in 0 until size) {
                val elevation = dem[originX + col, originY + row].toDouble()
                pixels[row * size + col] = ramp.colorAt(elevation).toArgb()
            }
        }

        canvas.drawImage(
            image = imageBitmapFromArgb(pixels, size, size),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(size, size),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(canvasSize, canvasSize),
            /* Upstream's `fragColor = u_opacity * texture(...)` on a premultiplied output is exactly
             * a scale of alpha, which is what `drawImage`'s alpha does. */
            alpha = opacity,
            filterQuality = if (resampling == RESAMPLING_NEAREST) {
                FilterQuality.None
            } else {
                FilterQuality.Low
            },
        )
    }
}
