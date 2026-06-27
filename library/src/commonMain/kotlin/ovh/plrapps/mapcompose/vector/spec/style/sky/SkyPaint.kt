package ovh.plrapps.mapcompose.vector.spec.style.sky

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

@Serializable
data class SkyPaint(
    @SerialName("sky-atmosphere-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val skyAtmosphereColor: ExpressionOrValue<Color>? = null,
    @SerialName("sky-atmosphere-halo-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val skyAtmosphereHaloColor: ExpressionOrValue<Color>? = null,
    @SerialName("sky-atmosphere-sun-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val skyAtmosphereSunColor: ExpressionOrValue<Color>? = null,
    @SerialName("sky-atmosphere-sun-intensity")
    val skyAtmosphereSunIntensity: ExpressionOrValue<Double>? = null,
    @SerialName("sky-gradient")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val skyGradient: ExpressionOrValue<Color>? = null,
    @SerialName("sky-gradient-radius")
    val skyGradientRadius: ExpressionOrValue<Double>? = null,
    @SerialName("sky-opacity")
    val skyOpacity: ExpressionOrValue<Double>? = null,
    @SerialName("sky-type")
    val skyType: ExpressionOrValue<String>? = null
) : PaintInterface