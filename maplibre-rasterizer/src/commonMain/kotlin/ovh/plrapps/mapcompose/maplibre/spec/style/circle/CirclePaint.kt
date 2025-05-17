package ovh.plrapps.mapcompose.maplibre.spec.style.circle

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValueColorSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CirclePaint(
    @SerialName("circle-radius")
    val circleRadius: ExpressionOrValue<Double>? = null,

    @SerialName("circle-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val circleColor: ExpressionOrValue<Color>? = null,

    @SerialName("circle-blur")
    val circleBlur: ExpressionOrValue<Double>? = null,

    @SerialName("circle-opacity")
    val circleOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("circle-translate")
    val circleTranslate: ExpressionOrValue<List<Double>>? = null,

    @SerialName("circle-translate-anchor")
    val circleTranslateAnchor: ExpressionOrValue<String>? = null,

    @SerialName("circle-pitch-scale")
    val circlePitchScale: ExpressionOrValue<String>? = null,

    @SerialName("circle-pitch-alignment")
    val circlePitchAlignment: ExpressionOrValue<String>? = null,

    @SerialName("circle-stroke-width")
    val circleStrokeWidth: ExpressionOrValue<Double>? = null,

    @SerialName("circle-stroke-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val circleStrokeColor: ExpressionOrValue<Color>? = null,

    @SerialName("circle-stroke-opacity")
    val circleStrokeOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("circle-sort-key")
    val circleSortKey: ExpressionOrValue<Double>? = null

) : PaintInterface