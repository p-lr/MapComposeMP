package ovh.plrapps.mapcompose.vector.spec.style.fill

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

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