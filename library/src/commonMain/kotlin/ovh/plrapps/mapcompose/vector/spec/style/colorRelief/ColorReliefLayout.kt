package ovh.plrapps.mapcompose.vector.spec.style.colorRelief

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class ColorReliefLayout(
    override val visibility: String? = "visible"
) : LayoutInterface
