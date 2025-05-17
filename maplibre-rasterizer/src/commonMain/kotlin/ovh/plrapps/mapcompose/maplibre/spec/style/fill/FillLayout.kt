package ovh.plrapps.mapcompose.maplibre.spec.style.fill

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FillLayout(
    @SerialName("fill-sort-key")
    val fillSortKey: ExpressionOrValue<Double>? = null,

    override val visibility: String? = "visible"
) : LayoutInterface