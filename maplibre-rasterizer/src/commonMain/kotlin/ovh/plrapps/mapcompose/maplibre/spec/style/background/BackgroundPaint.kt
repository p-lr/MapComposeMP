package ovh.plrapps.mapcompose.maplibre.spec.style.background

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.PaintInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValueColorSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackgroundPaint(
    @SerialName("background-color")
    @Serializable(with = ExpressionOrValueColorSerializer::class)
    val backgroundColor: ExpressionOrValue<Color>? = null,

    @SerialName("background-pattern")
    val backgroundPattern: ExpressionOrValue<String>? = null,

    @SerialName("background-opacity")
    val backgroundOpacity: ExpressionOrValue<Float>? = null
) : PaintInterface