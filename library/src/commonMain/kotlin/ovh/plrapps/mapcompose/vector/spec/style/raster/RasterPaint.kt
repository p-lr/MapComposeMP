package ovh.plrapps.mapcompose.vector.spec.style.raster

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

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
    @SerialName("raster-fade-duration")
    val rasterFadeDuration: ExpressionOrValue<Double>? = null
) : PaintInterface