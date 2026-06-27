package ovh.plrapps.mapcompose.vector.spec.style.raster

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class RasterLayout(
    override val visibility: String? = "visible"
) : LayoutInterface