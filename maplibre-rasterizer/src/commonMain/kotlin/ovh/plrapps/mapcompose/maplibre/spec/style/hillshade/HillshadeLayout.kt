package ovh.plrapps.mapcompose.maplibre.spec.style.hillshade

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class HillshadeLayout(
    override val visibility: String? = "visible"
) : LayoutInterface