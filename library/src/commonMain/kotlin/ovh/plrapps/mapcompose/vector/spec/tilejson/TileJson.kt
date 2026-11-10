package ovh.plrapps.mapcompose.vector.spec.tilejson

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

    /**
     * The source's tile size in pixels, and how its DEM tiles pack elevation into RGB.
     *
     * Neither is in the TileJSON spec: they are modelled because upstream reads both off the
     * *merged* object -- `pick(extend(tileJSON, options), [..., 'tileSize', 'encoding'])` in
     * `src/source/load_tilejson.ts`, whose result is then `extend`ed onto the source instance
     * (`raster_tile_source.ts#load`) -- so a TileJSON that serves one wins over the source's
     * default. Null means the document said nothing, which is what lets a style source's own value
     * survive the merge.
     */
    val tileSize: Int? = null,
    val encoding: String? = null,

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
