package ovh.plrapps.mapcompose.vector.spec.style.circle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

@Serializable
data class CircleLayout(
    @SerialName("circle-sort-key")
    val circleSortKey: ExpressionOrValue<Double>? = null,
    override val visibility: String? = "visible"
) : LayoutInterface