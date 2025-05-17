package ovh.plrapps.mapcompose.maplibre.spec.style.line

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LineLayout(
    @SerialName("line-cap")
    val lineCap: ExpressionOrValue<String>? = null,

    @SerialName("line-join")
    val lineJoin: ExpressionOrValue<String>? = null,

    @SerialName("line-miter-limit")
    val lineMiterLimit: ExpressionOrValue<Double>? = null,

    @SerialName("line-round-limit")
    val lineRoundLimit: ExpressionOrValue<Double>? = null,

    override val visibility: String? = "visible"
) : LayoutInterface