package ovh.plrapps.mapcompose.vector.spec.style.sky

import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class SkyLayout(
    override val visibility: String? = "visible"
) : LayoutInterface