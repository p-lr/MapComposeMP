package ovh.plrapps.mapcompose.maplibre.spec.style.sky

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class SkyLayout(
    override val visibility: String? = "visible"
) : LayoutInterface