package ovh.plrapps.mapcompose.maplibre.spec.style.circle

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CircleLayout(
    @SerialName("circle-sort-key")
    val circleSortKey: ExpressionOrValue<Double>? = null,
    override val visibility: String? = "visible"
) : LayoutInterface