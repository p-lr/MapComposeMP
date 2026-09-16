package ovh.plrapps.mapcompose.vector.spec.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.serializers.FeatureFilterSerializer
import ovh.plrapps.mapcompose.vector.spec.style.serializers.FilterHolder

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

    /**
     * `geojson` only: a filter applied to the document once, before it is cut into tiles.
     *
     * An ordinary boolean expression, compiled by the same serializer a layer's `filter` is, so
     * legacy v7 syntax is converted and a filter that fails to compile is a diagnostic rather than a
     * throw. Upstream applies it in the worker before `geojson-vt` sees the document
     * (`geojson_worker_source.ts`, `_filterGeoJSON`); see `GeoJson.applySourceFilter`.
     */
    @Serializable(with = FeatureFilterSerializer::class)
    val filter: FilterHolder? = null,

    /**
     * `geojson` only: replace every feature's id with its index in the *filtered* document.
     *
     * `geojson-vt`'s `convert.js`, whose `else if (options.generateId) id = index || 0` replaces an
     * id the document wrote rather than filling in for a missing one, and loses to `promoteId`.
     */
    val generateId: Boolean? = null,

    /**
     * `geojson` only: the tile buffer in style pixels, `0..512`; 128 when absent.
     *
     * Converted to tile units by `GeoJsonTiler.pixelsToTileUnits`, upstream's `_pixelsToTileUnits`.
     */
    val buffer: Double? = null,

    /**
     * `geojson` only: the Douglas-Peucker simplification tolerance in style pixels; 0.375 when
     * absent. Converted the same way [buffer] is.
     */
    val tolerance: Double? = null,

    /**
     * `geojson` only: recognised so that it can be *reported*; clustering is not supported.
     *
     * Modelled for the reason `encoding: "mlt"` is -- `json` has `ignoreUnknownKeys = true`, so an
     * unmodelled property is not an error, it is silence.
     */
    val cluster: Boolean? = null,

    /** `geojson` only: recognised so that it can be reported; `line-gradient` has no progress. */
    val lineMetrics: Boolean? = null,

    /** `raster` only: the source's tile size in pixels; 512 when it says nothing. */
    val tileSize: Int? = null,

    /** `[west, south, east, north]`; parsed but not yet honoured -- see the raster painter. */
    val bounds: List<Double>? = null,

    /**
     * The feature property that stands in for a feature's id, on a `vector` or `geojson` source.
     *
     * Kept as raw JSON because the spec allows either a bare property name or an object naming one
     * per source layer -- the same reason `sprite` and `font-faces` are. See [promoteIdSpec], which
     * normalizes it, and `renderer/BaseRenderer.buildEvalFeature`, which applies it.
     */
    val promoteId: JsonElement? = null,

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
            /* Upstream's `coerceSpriteToArray` (`src/util/style.ts`) gives the single-URL form the
             * id `default`, which is exactly the id whose entries are not namespaced. */
            is JsonPrimitive -> listOf(
                SpriteSource(id = SpriteManager.DEFAULT_SPRITE_ID, url = element.contentOrNull)
            )
            is JsonArray -> {
                json.decodeFromJsonElement(ListSerializer(SpriteSource.serializer()), element)
            }
            is JsonObject,
            JsonNull,
            null -> emptyList()
        }
    }