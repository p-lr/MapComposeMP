package ovh.plrapps.mapcompose.vector.spec.style.heatmap

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.VISIBILITY_DEFAULT
import ovh.plrapps.mapcompose.vector.spec.style.props.VisibilitySerializer

@Serializable
data class HeatmapLayout(
    @Serializable(with = VisibilitySerializer::class)
    override val visibility: ExpressionOrValue<String>? = VISIBILITY_DEFAULT
) : LayoutInterface