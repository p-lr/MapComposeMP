package ovh.plrapps.mapcompose.maplibre.spec.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.maplibre.data.json

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
    @SerialName("sprite") var sprite: JsonElement? = null,
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

@Serializable
data class SpriteSource(val id: String?, val url: String?)

val MapLibreStyle.sprites: List<SpriteSource>
    get() {
        val element = this.sprite
        return when(element) {
            is JsonPrimitive -> listOf(SpriteSource(id = "", element.contentOrNull))
            is JsonArray -> {
                json.decodeFromJsonElement(ListSerializer(SpriteSource.serializer()), element)
            }
            is JsonObject,
            JsonNull,
            null -> emptyList()
        }
    }