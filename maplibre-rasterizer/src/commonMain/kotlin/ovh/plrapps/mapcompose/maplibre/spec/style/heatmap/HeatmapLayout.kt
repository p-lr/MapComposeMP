package ovh.plrapps.mapcompose.maplibre.spec.style.heatmap

import ovh.plrapps.mapcompose.maplibre.spec.style.LayoutInterface
import kotlinx.serialization.Serializable

@Serializable
data class HeatmapLayout(
    override val visibility: String? = "visible"
) : LayoutInterface