package ovh.plrapps.mapcompose.maplibre.spec.style.fillExtrusion

import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FillExtrusionPaint(
    @SerialName("fill-extrusion-opacity")
    val fillExtrusionOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("fill-extrusion-color")
    val fillExtrusionColor: ExpressionOrValue<String>? = null,

    @SerialName("fill-extrusion-translate")
    val fillExtrusionTranslate: ExpressionOrValue<List<Double>>? = null,

    @SerialName("fill-extrusion-translate-anchor")
    val fillExtrusionTranslateAnchor: ExpressionOrValue<String>? = null,

    @SerialName("fill-extrusion-pattern")
    val fillExtrusionPattern: ExpressionOrValue<String>? = null,

    @SerialName("fill-extrusion-height")
    val fillExtrusionHeight: ExpressionOrValue<Double>? = null,

    @SerialName("fill-extrusion-base")
    val fillExtrusionBase: ExpressionOrValue<Double>? = null,

    @SerialName("fill-extrusion-vertical-gradient")
    val fillExtrusionVerticalGradient: ExpressionOrValue<Boolean>? = null
) : PaintInterface