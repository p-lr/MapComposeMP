package ovh.plrapps.mapcompose.maplibre.spec.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * https://maplibre.org/maplibre-style-spec/
 */
@Serializable
data class MapLibreStyle(
    @SerialName("version") var version: Int? = null,
    @SerialName("name") var name: String? = null,
    @SerialName("center") var center: List<Double> = emptyList(),
    @SerialName("zoom") var zoom: Float? = null,
    @SerialName("bearing") var bearing: Int? = null,
    @SerialName("pitch") var pitch: Int? = null,
    @SerialName("sources") var sources: Map<String, Source>? = emptyMap(),
    @SerialName("sprite") var sprite: String? = null,
    @SerialName("glyphs") var glyphs: String? = null,
    @SerialName("layers") var layers: List<Layer> = emptyList(),
    @SerialName("id") var id: String? = null
)

@Serializable
data class Source(
    val type: String? = null,

    // For TileJSON reference
    val url: String? = null,

    // For inline tile source definition
    val tiles: List<String>? = null,
    val minzoom: Int? = null,
    val maxzoom: Int? = null,
    val attribution: String? = null
)