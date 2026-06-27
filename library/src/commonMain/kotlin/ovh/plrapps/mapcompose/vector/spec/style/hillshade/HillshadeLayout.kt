package ovh.plrapps.mapcompose.vector.spec.style.hillshade

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class HillshadeLayout(
    override val visibility: String? = "visible"
) : LayoutInterface