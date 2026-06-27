package ovh.plrapps.mapcompose.vector.spec.style.line

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

@Serializable
data class LinePaint(
    @SerialName("line-opacity")
    val lineOpacity: ExpressionOrValue<Double>? = null,

    @SerialName("line-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val lineColor: ExpressionOrValue<Color>? = null,

    @SerialName("line-width")
    val lineWidth: ExpressionOrValue<Double>? = null,

    @SerialName("line-offset")
    val lineOffset: ExpressionOrValue<Double>? = null,

    @SerialName("line-blur")
    val lineBlur: ExpressionOrValue<Double>? = null,

    @SerialName("line-dasharray")
    val lineDasharray: ExpressionOrValue<List<Double>>? = null,

    @SerialName("line-pattern")
    val linePattern: ExpressionOrValue<String>? = null,

    @SerialName("line-translate")
    val lineTranslate: ExpressionOrValue<List<Double>>? = null,

    @SerialName("line-translate-anchor")
    val lineTranslateAnchor: ExpressionOrValue<String>? = null,

    @SerialName("line-gap-width")
    val lineGapWidth: ExpressionOrValue<Double>? = null
) : PaintInterface