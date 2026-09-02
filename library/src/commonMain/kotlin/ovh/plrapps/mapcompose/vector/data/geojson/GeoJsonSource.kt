package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.resolveOverscaled
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Source

/**
 * A loaded `geojson` source: its features, already projected, and the zoom range it serves.
 *
 * Unlike every other source type there is no server -- the whole document is held in memory and
 * [tile] cuts whatever the renderer asks for out of it, which is what MapLibre does with
 * `geojson-vt`.
 *
 * Not supported, and documented as such rather than silently approximated: `cluster` and its
 * options, and `lineMetrics` (so a `line-gradient` over a geojson source has no line progress).
 */
class GeoJsonSource(
    features: List<GeoJsonFeature>,
    val minZoom: Int = 0,
    val maxZoom: Int = 18,
) {
    private val tiler = GeoJsonTiler(features)

    /**
     * Which tile of this source covers the map tile at [z]/[x]/[y], and which part of it.
     *
     * geojson-vt stops cutting at the source's `maxzoom`, so above it MapLibre overzooms the same
     * way it does a served vector source -- see [resolveOverscaled]. The tiler here could cut at any
     * zoom, but doing so would drop the simplification the spec's `maxzoom` is there to fix.
     */
    fun resolve(z: Int, x: Int, y: Int): TileRef? =
        resolveOverscaled(z = z, x = x, y = y, minZoom = minZoom, maxZoom = maxZoom)

    /** The tile [ref] names, or `null` where it is empty. */
    fun tile(ref: TileRef): Tile? = tiler.tile(ref.z, ref.x, ref.y)

    /** The tile at `(z, x, y)`, or `null` outside the source's zoom range or where it is empty. */
    fun tile(z: Int, x: Int, y: Int): Tile? = resolve(z, x, y)?.let { tile(it) }

    companion object {
        /**
         * Loads one `geojson` source.
         *
         * `data` is either the document itself or a URL pointing at one; the spec allows both in
         * the same field, so a string is treated as a URL and anything else as inline JSON.
         *
         * Returns `null` when the source declares no data or the fetch fails -- a source that
         * cannot be loaded should leave its layers empty, not abort the style.
         */
        suspend fun load(
            source: Source,
            loadResource: suspend (String) -> RawSource?,
        ): GeoJsonSource? {
            val data = source.data ?: return null
            val document: JsonElement = if (data is JsonPrimitive && data.isString) {
                val text = runCatching {
                    withContext(IODispatcher) { loadResource(data.content)?.buffered()?.readString() }
                }.getOrNull() ?: return null
                runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
            } else {
                data
            }

            val features = GeoJson.parse(document)
            if (features.isEmpty()) return null
            return GeoJsonSource(
                features = features,
                minZoom = source.minzoom ?: 0,
                maxZoom = source.maxzoom ?: DEFAULT_MAX_ZOOM,
            )
        }

        /** `geojson-vt`'s own default, and what MapLibre uses when a source says nothing. */
        const val DEFAULT_MAX_ZOOM = 18
    }
}
