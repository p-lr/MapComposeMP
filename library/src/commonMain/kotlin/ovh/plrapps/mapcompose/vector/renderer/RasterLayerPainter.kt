package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.renderer.utils.rasterColorMatrix
import ovh.plrapps.mapcompose.vector.spec.style.RESAMPLING_NEAREST
import ovh.plrapps.mapcompose.vector.spec.style.RasterLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

/**
 * The part of a decoded raster tile that covers one map tile.
 *
 * [srcOffset] and [srcSize] are the crop within [image]: the whole image when the source has the
 * requested zoom, and one sub-square of it when the source is overzoomed -- see
 * [ovh.plrapps.mapcompose.vector.data.MapLibreTileSource.resolve].
 */
class RasterTileImage(
    val image: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
) {
    companion object {
        /**
         * Crops [image] to the sub-square [ref] names.
         *
         * Returns `null` when the crop would be empty, which happens only if a source is overzoomed
         * so far that a sub-square is narrower than a pixel -- there is nothing meaningful to
         * magnify at that point.
         */
        fun of(image: ImageBitmap, ref: TileRef): RasterTileImage? {
            if (ref.span <= 1) {
                return RasterTileImage(image, IntOffset.Zero, IntSize(image.width, image.height))
            }
            val width = image.width / ref.span
            val height = image.height / ref.span
            if (width <= 0 || height <= 0) return null
            return RasterTileImage(
                image = image,
                srcOffset = IntOffset(ref.subX * width, ref.subY * height),
                srcSize = IntSize(width, height),
            )
        }
    }
}

/**
 * Draws `raster` layers -- one image tile covering the whole map tile.
 *
 * Follows maplibre-gl-js `src/render/draw_raster.ts` and `src/shaders/raster.fragment.glsl`. The
 * colour adjustments (`raster-hue-rotate`, `-saturation`, `-contrast`, `-brightness-min`,
 * `-brightness-max`) collapse into one [ColorFilter]; see
 * [ovh.plrapps.mapcompose.vector.renderer.utils.rasterColorMatrix]. `raster-opacity` is not part of
 * it because upstream applies it to alpha alone, which [DrawScope.drawImage]'s `alpha` already does.
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

        canvas.drawImage(
            image = image.image,
            srcOffset = image.srcOffset,
            srcSize = image.srcSize,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(canvasSize, canvasSize),
            alpha = opacity,
            colorFilter = colorFilter,
            filterQuality = filterQuality,
        )
    }
}
