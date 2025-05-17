package ovh.plrapps.mapcompose.maplibre.spec.style.raster

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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