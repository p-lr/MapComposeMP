package ovh.plrapps.mapcompose.vector.spec.style.background

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class BackgroundLayout(
    override val visibility: String? = "visible"
) : LayoutInterface