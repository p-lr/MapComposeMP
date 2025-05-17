package ovh.plrapps.mapcompose.maplibre.spec.style.raster

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class RasterLayout(
    override val visibility: String? = "visible"
) : LayoutInterface