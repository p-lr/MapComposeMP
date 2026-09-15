package ovh.plrapps.mapcompose.vector.spec.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.vector.data.json

/**
 * https://maplibre.org/maplibre-style-spec/
 */
@Serializable
data class MapLibreStyle(
    @SerialName("version") var version: Int? = null,
    @SerialName("name") var name: String? = null,
    @SerialName("center") var center: List<Double> = emptyList(),
    @SerialName("zoom") var zoom: Float? = null,
    @SerialName("bearing") var bearing: Double? = null,
    @SerialName("pitch") var pitch: Double? = null,
    @SerialName("sources") var sources: Map<String, Source>? = emptyMap(),
    @SerialName("sprite") var sprite: JsonElement? = null,
    @SerialName("glyphs") var glyphs: String? = null,
    @SerialName("layers") var layers: List<Layer> = emptyList(),
    @SerialName("id") var id: String? = null,

    /**
     * The `global-state` declaration block: one entry per state property, each with its default.
     *
     * Modelled so it round-trips -- `json` has `ignoreUnknownKeys = true`, so an unmodelled root
     * property is silently dropped and lost on re-serialization. The defaults themselves reach the
     * expression engine through `StyleGlobalState`, not from here: they have to be known *before*
     * the layers are decoded, and JSON promises nothing about key order.
     */
    @SerialName("state") var state: Map<String, StateSpec>? = null,

    /**
     * The `font-faces` declaration block: one entry per `text-font` name, each naming the font
     * file(s) to draw it with and, optionally, the codepoints each file covers.
     *
     * Kept as raw JSON because an entry may be a URL string, one object or a list of either -- the
     * same reason `sprite` is. See the [fontFaces] extension, which normalizes it, and
     * `data/glyphs/FontFaceManager.kt`, which draws with it.
     */
    @SerialName("font-faces") var fontFacesJson: Map<String, JsonElement>? = null,
)

/**
 * One entry of a style's `state` block.
 *
 * The spec types the value `{"default": {"type": "*"}}`, i.e. any JSON at all, so it is kept as a
 * [JsonElement] and normalized to an engine value by `globalStateDefaults`.
 */
@Serializable
data class StateSpec(
    @SerialName("default") val default: JsonElement? = null,
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
    val scheme: String? = null,
    val attribution: String? = null,

    /**
     * `geojson` only: the document itself, or the URL of one.
     *
     * The style spec allows either an inline `FeatureCollection` and a URL string in the same
     * field, so it is kept as raw JSON and told apart when the source is loaded.
     */
    val data: JsonElement? = null,

    /** `raster` only: the source's tile size in pixels; 512 when it says nothing. */
    val tileSize: Int? = null,

    /** `[west, south, east, north]`; parsed but not yet honoured -- see the raster painter. */
    val bounds: List<Double>? = null,

    /** `geojson` only: the feature property to promote to the feature's id. */
    val promoteId: String? = null,

    /* raster-dem only: how the tile's RGB channels encode elevation. See DemUnpack. */
    val encoding: String? = null,
    val redFactor: Double? = null,
    val greenFactor: Double? = null,
    val blueFactor: Double? = null,
    val baseShift: Double? = null,
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