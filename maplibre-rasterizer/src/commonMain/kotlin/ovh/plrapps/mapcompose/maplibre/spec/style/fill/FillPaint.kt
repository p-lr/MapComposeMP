package ovh.plrapps.mapcompose.maplibre.spec.style.fill

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValueColorSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FillPaint(
    @SerialName("fill-antialias")
    val fillAntialias: ExpressionOrValue<Boolean>? = null,

    @SerialName("fill-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val fillColor: ExpressionOrValue<Color>? = null,

    @SerialName("fill-opacity")
    val fillOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("fill-outline-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val fillOutlineColor: ExpressionOrValue<Color>? = null,

    @SerialName("fill-pattern")
    val fillPattern: ExpressionOrValue<String>? = null,

    @SerialName("fill-translate")
    val fillTranslate: ExpressionOrValue<List<Double>>? = null,

    @SerialName("fill-translate-anchor")
    val fillTranslateAnchor: ExpressionOrValue<String>? = null
) : PaintInterface