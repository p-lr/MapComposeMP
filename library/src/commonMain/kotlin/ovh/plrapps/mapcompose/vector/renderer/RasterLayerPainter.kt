package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.renderer.utils.rasterColorMatrix
import ovh.plrapps.mapcompose.vector.renderer.utils.sampleWindow
import ovh.plrapps.mapcompose.vector.spec.style.RESAMPLING_NEAREST
import ovh.plrapps.mapcompose.vector.spec.style.RasterLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

/**
 * A decoded raster tile and which map tile is being drawn from it.
 *
 * [ref] is the whole tile in the common case, and one sub-square of an overzoomed ancestor
 * otherwise -- see [ovh.plrapps.mapcompose.vector.data.MapLibreTileSource.resolve]. The sub-square
 * is resolved at draw time by
 * [ovh.plrapps.mapcompose.vector.renderer.utils.sampleWindow] rather than being cropped here,
 * because it is fractional once the source is overzoomed past its own pixel count.
 */
class RasterTileImage(
    val image: ImageBitmap,
    val ref: TileRef,
)

/**
 * Draws `raster` layers -- one image tile covering the whole map tile.
 *
 * Follows maplibre-gl-js `src/render/draw_raster.ts` and `src/shaders/raster.fragment.glsl`. The
 * colour adjustments (`raster-hue-rotate`, `-saturation`, `-contrast`, `-brightness-min`,
 * `-brightness-max`) collapse into one [ColorFilter]; see
 * [ovh.plrapps.mapcompose.vector.renderer.utils.rasterColorMatrix]. `raster-opacity` is not part of
 * it because upstream applies it to alpha alone, which [DrawScope.drawImage]'s `alpha` already does.
 *
 * An overzoomed source is magnified over a **fractional** window of the ancestor's pixels, which is
 * what upstream's texture coordinates amount to; see
 * [ovh.plrapps.mapcompose.vector.renderer.utils.SampleWindow] for why the window cannot be an
 * integer crop.
 *
 * This does not extend [BaseLayerPainter]: that contract is shaped around a feature, an `extent` and
 * a `source-layer`, and a raster layer has none of them -- the same reason [SymbolBucketBuilder] sits
 * outside it. The class is stateless, so [TileRenderer] keeps one instance rather than one per
 * layer.
 *
 * Known divergences, both consequences of rasterizing tiles to bitmaps rather than sampling textures
 * on the GPU:
 * - **`raster-fade-duration` is inert.** Upstream cross-fades a tile against its parent over that
 *   many milliseconds as it loads. A tile here is rasterized once and handed to the tile pipeline as
 *   bytes; there is no frame loop, and no per-tile load timeline to fade against.
 * - **The image is resampled twice**, once into the tile bitmap and again when that bitmap is drawn
 *   (see `vectorTileBitmapSize`). Drawing into the tile bitmap is what keeps a raster layer in style
 *   order relative to the vector layers around it.
 */
class RasterLayerPainter {

    suspend fun paint(
        canvas: DrawScope,
        style: RasterLayer,
        image: RasterTileImage,
        canvasSize: Int,
        actualZoom: Double,
    ) {
        val paint = style.paint

        val opacity = paint.rasterOpacity.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_OPACITY.toFloat()
        val hueRotate = paint.rasterHueRotate.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_HUE_ROTATE.toFloat()
        val saturation = paint.rasterSaturation.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_SATURATION.toFloat()
        val contrast = paint.rasterContrast.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_CONTRAST.toFloat()
        val brightnessMin = paint.rasterBrightnessMin.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_BRIGHTNESS_MIN.toFloat()
        val brightnessMax = paint.rasterBrightnessMax.processAsFloat(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_BRIGHTNESS_MAX.toFloat()
        val resampling = paint.rasterResampling.processAsString(zoom = actualZoom)
            ?: StyleSpecDefaults.RASTER_RESAMPLING

        val colorFilter = rasterColorMatrix(
            hueRotate = hueRotate,
            saturation = saturation,
            contrast = contrast,
            brightnessMin = brightnessMin,
            brightnessMax = brightnessMax,
        )?.let { ColorFilter.colorMatrix(it) }

        /* A raster tile is magnified far more often than it is minified here, so `linear` maps onto
         * bilinear rather than one of the mipmapped qualities, matching upstream's TEXTURE filter. */
        val filterQuality = if (resampling == RESAMPLING_NEAREST) {
            FilterQuality.None
        } else {
            FilterQuality.Low
        }

        val bitmap = image.image
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val ref = image.ref
        /* The axes are resolved separately because nothing guarantees a square source image, as the
         * integer crop this replaced did not assume one either. */
        val x = sampleWindow(dim = bitmap.width, sub = ref.subX, span = ref.span, canvasSize = canvasSize)
        val y = sampleWindow(dim = bitmap.height, sub = ref.subY, span = ref.span, canvasSize = canvasSize)

        /* The window is placed by a transform rather than by `dstOffset`/`dstSize`, which are
         * integers and cannot express a sub-pixel origin or a magnification that is not a whole
         * number of pixels per sample. The tile bitmap is the clip, so the overhang costs nothing. */
        canvas.translate(x.origin, y.origin) {
            scale(scaleX = x.scale, scaleY = y.scale, pivot = Offset.Zero) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset(x.first, y.first),
                    srcSize = IntSize(x.count, y.count),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(x.count, y.count),
                    alpha = opacity,
                    colorFilter = colorFilter,
                    filterQuality = filterQuality,
                )
            }
        }
    }
}
