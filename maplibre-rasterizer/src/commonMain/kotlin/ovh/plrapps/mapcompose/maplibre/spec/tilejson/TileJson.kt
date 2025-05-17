package ovh.plrapps.mapcompose.maplibre.spec.tilejson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TileJson(
    val tilejson: String,
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val attribution: String? = null,
    val template: String? = null,
    val legend: String? = null,
    val scheme: String = "xyz",
    val tiles: List<String>,
    val grids: List<String>? = null,
    val data: List<String>? = null,
    val minzoom: Int = 0,
    val maxzoom: Int = 22,
    val bounds: List<Double>? = null, // [west, south, east, north]
    val center: List<Double>? = null, // [lon, lat, zoom]

    @SerialName("vector_layers")
    val vectorLayers: List<VectorLayer>? = null
)

@Serializable
data class VectorLayer(
    val id: String,
    val description: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val minzoom: Int? = null,
    val maxzoom: Int? = null
)
