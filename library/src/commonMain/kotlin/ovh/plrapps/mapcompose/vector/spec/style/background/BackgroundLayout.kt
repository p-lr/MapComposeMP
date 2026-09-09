package ovh.plrapps.mapcompose.vector.spec.style.background

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.VISIBILITY_DEFAULT
import ovh.plrapps.mapcompose.vector.spec.style.props.VisibilitySerializer

@Serializable
data class BackgroundLayout(
    @Serializable(with = VisibilitySerializer::class)
    override val visibility: ExpressionOrValue<String>? = VISIBILITY_DEFAULT
) : LayoutInterface