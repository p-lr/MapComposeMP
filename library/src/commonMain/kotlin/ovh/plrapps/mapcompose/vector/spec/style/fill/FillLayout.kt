package ovh.plrapps.mapcompose.vector.spec.style.fill

import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.props.VISIBILITY_DEFAULT
import ovh.plrapps.mapcompose.vector.spec.style.props.VisibilitySerializer

@Serializable
data class FillLayout(
    @SerialName("fill-sort-key")
    val fillSortKey: ExpressionOrValue<Double>? = null,

    @Serializable(with = VisibilitySerializer::class)
    override val visibility: ExpressionOrValue<String>? = VISIBILITY_DEFAULT
) : LayoutInterface