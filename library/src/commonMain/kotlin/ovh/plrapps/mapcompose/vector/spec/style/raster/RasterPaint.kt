package ovh.plrapps.mapcompose.vector.spec.style.raster

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

/**
 * `raster`'s paint properties.
 *
 * [resampling] and [rasterResampling] are two spellings of one thing: the style spec declares both
 * on `paint_raster`, and upstream reads both -- `webgl/draw/draw_raster.ts` is
 * `layer.paint.get('resampling') === 'nearest' || layer.paint.get('raster-resampling') === 'nearest'`.
 * Modelling only the prefixed one made `"resampling": "nearest"` silently draw bilinear.
 */
@Serializable
data class RasterPaint(
    @SerialName("raster-opacity")
    val rasterOpacity: ExpressionOrValue<Double>? = null,
    @SerialName("raster-hue-rotate")
    val rasterHueRotate: ExpressionOrValue<Double>? = null,
    @SerialName("raster-brightness-min")
    val rasterBrightnessMin: ExpressionOrValue<Double>? = null,
    @SerialName("raster-brightness-max")
    val rasterBrightnessMax: ExpressionOrValue<Double>? = null,
    @SerialName("raster-saturation")
    val rasterSaturation: ExpressionOrValue<Double>? = null,
    @SerialName("raster-contrast")
    val rasterContrast: ExpressionOrValue<Double>? = null,
    @SerialName("raster-resampling")
    val rasterResampling: ExpressionOrValue<String>? = null,
    /** The unprefixed spelling, as `hillshade` and `color-relief` have it; see the class KDoc. */
    @SerialName("resampling")
    val resampling: ExpressionOrValue<String>? = null,
    @SerialName("raster-fade-duration")
    val rasterFadeDuration: ExpressionOrValue<Double>? = null
) : PaintInterface