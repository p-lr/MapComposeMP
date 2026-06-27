package ovh.plrapps.mapcompose.vector.spec.style.fillExtrusion

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class FillExtrusionLayout(
    override val visibility: String? = "visible"

) : LayoutInterface