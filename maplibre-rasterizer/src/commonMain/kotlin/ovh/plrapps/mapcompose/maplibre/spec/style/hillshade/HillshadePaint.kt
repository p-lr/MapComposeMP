package ovh.plrapps.mapcompose.maplibre.spec.style.hillshade

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValueColorSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HillshadePaint(
    @SerialName("hillshade-accent-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val hillshadeAccentColor: ExpressionOrValue<Color>? = null,

    @SerialName("hillshade-exaggeration")
    val hillshadeExaggeration: ExpressionOrValue<Double>? = null,

    @SerialName("hillshade-highlight-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val hillshadeHighlightColor: ExpressionOrValue<Color>? = null,

    @SerialName("hillshade-illumination-anchor")
    val hillshadeIlluminationAnchor: ExpressionOrValue<String>? = null,

    @SerialName("hillshade-illumination-direction")
    val hillshadeIlluminationDirection: ExpressionOrValue<Double>? = null,

    @SerialName("hillshade-shadow-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val hillshadeShadowColor: ExpressionOrValue<Color>? = null
) : PaintInterface