package ovh.plrapps.mapcompose.vector.spec.style.background

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.PaintInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValueColorSerializer

@Serializable
data class BackgroundPaint(
    @SerialName("background-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val backgroundColor: ExpressionOrValue<Color>? = null,

    @SerialName("background-pattern")
    val backgroundPattern: ExpressionOrValue<String>? = null,

    @SerialName("background-opacity")
    val backgroundOpacity: ExpressionOrValue<Double>? = null
) : PaintInterface