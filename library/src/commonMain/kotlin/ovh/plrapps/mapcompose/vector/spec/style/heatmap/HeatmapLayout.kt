package ovh.plrapps.mapcompose.vector.spec.style.heatmap

import kotlinx.serialization.Serializable
import ovh.plrapps.mapcompose.vector.spec.style.LayoutInterface

@Serializable
data class HeatmapLayout(
    override val visibility: String? = "visible"
) : LayoutInterface