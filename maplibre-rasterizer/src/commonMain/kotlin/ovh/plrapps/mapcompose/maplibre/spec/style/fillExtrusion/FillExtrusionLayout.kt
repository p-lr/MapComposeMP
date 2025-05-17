package ovh.plrapps.mapcompose.maplibre.spec.style.fillExtrusion

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class FillExtrusionLayout(
    override val visibility: String? = "visible"

) : LayoutInterface