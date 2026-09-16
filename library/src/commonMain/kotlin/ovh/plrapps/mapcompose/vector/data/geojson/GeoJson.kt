package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.filter.FeatureFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

/**
 * A GeoJSON feature, projected and ready to be cut into tiles.
 *
 * Coordinates are already in normalized Web Mercator -- `(0, 0)` at the north-west corner of the
 * world and `(1, 1)` at the south-east -- because a feature is projected once when the source is
 * loaded rather than once per tile it appears in. That is the same space `MapState` uses for
 * everything else.
 *
 * [rings] holds the geometry uniformly: one entry for a point, one per line, one per polygon ring.
 * The distinction is [type], which is also what tells a painter whether to fill or stroke it.
 */
class GeoJsonFeature(
    val type: Tile.GeomType,
    val rings: List<List<GeoJsonPoint>>,
    val properties: Map<String, Any?>,
    /**
     * The feature's own `id`, normalized to an engine value: a [Double] for a number, a [String]
     * for a string, `null` for neither.
     *
     * RFC 7946 allows both, and so does MapLibre -- a string id is not rounded to a number and a
     * quoted `"42"` stays a string. That is why this is not the `Long` the MVT wire format can
     * carry; see `GeoJsonTiler.SYNTHETIC_ID_KEY` for how a non-integral one crosses the synthetic
     * tile.
     */
    val id: Any?,
)

class GeoJsonPoint(val x: Double, val y: Double)

/**
 * Reads a GeoJSON document into projected features.
 *
 * Accepts what the style spec's `data` accepts: a `FeatureCollection`, a bare `Feature`, or a bare
 * geometry. Anything it cannot understand is skipped rather than failing the source -- a single
 * malformed feature should cost that feature, not the layer.
 *
 * `GeometryCollection` is flattened into one feature per member geometry, each carrying the
 * collection's properties, which is how upstream's `geojson-vt` handles it.
 */
object GeoJson {

    /**
     * Reads a document into projected features.
     *
     * [generateId] is the source option: every feature's id becomes its index in the document's
     * top-level `features` array, which is `geojson-vt`'s `convert.js`
     * (`let id = geojson.id; … else if (options.generateId) id = index || 0`). It *replaces* an id
     * the document wrote rather than filling in for a missing one, and a bare `Feature` -- which
     * upstream converts with no index at all -- gets 0.
     *
     * Because the index is the position in the array *as handed over*, [applySourceFilter] has to
     * run before this, exactly as upstream's worker filters `data.features` before geojson-vt
     * indexes what survived.
     */
    fun parse(element: JsonElement?, generateId: Boolean = false): List<GeoJsonFeature> {
        val out = mutableListOf<GeoJsonFeature>()
        readInto(element, out, properties = emptyMap(), id = null, generateId = generateId, index = null)
        return out
    }

    private fun readInto(
        element: JsonElement?,
        out: MutableList<GeoJsonFeature>,
        properties: Map<String, Any?>,
        id: Any?,
        generateId: Boolean,
        index: Int?,
    ) {
        val obj = element as? JsonObject ?: return
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull()) {
            "FeatureCollection" -> {
                val features = obj["features"] as? JsonArray ?: return
                features.forEachIndexed { position, feature ->
                    readInto(feature, out, properties, id, generateId, index = position)
                }
            }

            "Feature" -> {
                val ownProperties = readProperties(obj["properties"])
                val ownId = if (generateId) (index ?: 0).toDouble() else readId(obj["id"])
                readInto(obj["geometry"], out, ownProperties, ownId, generateId, index)
            }

            "GeometryCollection" -> {
                val geometries = obj["geometries"] as? JsonArray ?: return
                for (geometry in geometries) readInto(geometry, out, properties, id, generateId, index)
            }

            else -> readGeometry(obj, properties, id)?.let { out += it }
        }
    }

    /**
     * A `geojson` source's own `filter`, applied to the document before it is cut into tiles.
     *
     * A port of `_filterGeoJSON` / `_getFilterPredicate` (`src/source/geojson_worker_source.ts`),
     * which upstream runs in the worker so that `geojson-vt` never sees an excluded feature. Only a
     * `FeatureCollection` has a `features` array to filter; every other document is returned as it
     * came, which is what upstream's `data.features.filter(...)` amounts to.
     *
     * The filter is evaluated at **zoom 0** with no tile, as upstream's
     * `compiled.value.evaluate({zoom: 0}, feature)` is, so `within` answers `false` and `distance`
     * `NaN` -- there is no canonical tile id to project against at load time, and upstream passes
     * none either.
     *
     * **One divergence.** [EvalFeature.type] is the feature's real geometry type, where upstream
     * hands `evaluate` the raw GeoJSON `Feature` object, whose `.type` is the literal string
     * `"Feature"` -- so `["geometry-type"]` and `$type` match nothing at all in an upstream source
     * filter. Reproducing that is of no use to anybody.
     */
    fun applySourceFilter(element: JsonElement?, filter: FeatureFilter): JsonElement? {
        val obj = element as? JsonObject ?: return element
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull() != "FeatureCollection") return element
        val features = obj["features"] as? JsonArray ?: return element

        val globals = GlobalProperties(zoom = 0.0)
        val kept = features.filter { entry ->
            val feature = entry as? JsonObject ?: return@filter false
            filter.filter(
                globals = globals,
                feature = EvalFeature(
                    type = evalGeometryTypeOf(feature["geometry"]),
                    id = readId(feature["id"]),
                    properties = readProperties(feature["properties"]),
                ),
            )
        }
        if (kept.size == features.size) return element
        return buildJsonObject {
            for ((key, value) in obj) if (key != "features") put(key, value)
            put("features", JsonArray(kept))
        }
    }

    /** The geometry type an expression sees, from a raw GeoJSON geometry object. */
    private fun evalGeometryTypeOf(geometry: JsonElement?): String {
        val type = ((geometry as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull()
        return when (type) {
            "Point", "MultiPoint" -> "Point"
            "LineString", "MultiLineString" -> "LineString"
            "Polygon", "MultiPolygon" -> "Polygon"
            else -> "Unknown"
        }
    }

    private fun readGeometry(
        obj: JsonObject,
        properties: Map<String, Any?>,
        id: Any?,
    ): GeoJsonFeature? {
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull() ?: return null
        val coordinates = obj["coordinates"] as? JsonArray ?: return null

        val rings: List<List<GeoJsonPoint>> = when (type) {
            "Point" -> listOfNotNull(readPosition(coordinates)?.let { listOf(it) })
            "MultiPoint" -> listOfNotNull(readLine(coordinates).takeIf { it.isNotEmpty() })
            "LineString" -> listOfNotNull(readLine(coordinates).takeIf { it.size >= 2 })
            "MultiLineString" -> coordinates.mapNotNull { line ->
                (line as? JsonArray)?.let { readLine(it) }?.takeIf { it.size >= 2 }
            }

            "Polygon" -> readPolygon(coordinates)
            "MultiPolygon" -> coordinates.flatMap { polygon ->
                (polygon as? JsonArray)?.let { readPolygon(it) } ?: emptyList()
            }

            else -> return null
        }
        if (rings.isEmpty()) return null

        val geomType = when (type) {
            "Point", "MultiPoint" -> Tile.GeomType.POINT
            "LineString", "MultiLineString" -> Tile.GeomType.LINESTRING
            else -> Tile.GeomType.POLYGON
        }
        return GeoJsonFeature(geomType, rings, properties, id)
    }

    /**
     * One polygon's rings, rewound so the first is an exterior ring and the rest are holes.
     *
     * RFC 7946 asks for right-hand-rule winding but says a parser must accept anything, and
     * `geojson-vt` rewinds on the way in (`src/tile.ts`). Nothing downstream can recover it: rings
     * are grouped by the *sign* of their signed area (`classifyRings`), so a hole wound like its
     * exterior reads as a second polygon and `PathFillType.NonZero` fills the hole in instead of
     * cutting it out.
     *
     * Rewinding here rather than at encode time is what lets the tiler, the clipper and the
     * simplifier all see correct winding; a `MultiPolygon` gets it per polygon for free, since this
     * is called once per polygon there.
     */
    private fun readPolygon(coordinates: JsonArray): List<List<GeoJsonPoint>> =
        coordinates.mapNotNull { ring ->
            (ring as? JsonArray)?.let { readLine(it) }?.takeIf { it.size >= 3 }
        }.mapIndexed { index, ring ->
            // `classifyRings`: a positive signed area is an exterior ring, a negative one a hole.
            val wantsPositive = index == 0
            if ((signedArea(ring) < 0.0) == wantsPositive) ring.reversed() else ring
        }

    /**
     * Twice a ring's signed area, in the same orientation `classifyRings` reads.
     *
     * Transcribed from `classify_rings.ts`'s `calculateSignedArea` -- the two have to agree on the
     * sign or the rewind would be the wrong way round.
     */
    private fun signedArea(ring: List<GeoJsonPoint>): Double {
        var sum = 0.0
        var j = ring.size - 1
        for (i in ring.indices) {
            val p1 = ring[i]
            val p2 = ring[j]
            sum += (p2.x - p1.x) * (p1.y + p2.y)
            j = i
        }
        return sum
    }

    private fun readLine(coordinates: JsonArray): List<GeoJsonPoint> =
        coordinates.mapNotNull { position -> (position as? JsonArray)?.let { readPosition(it) } }

    /** Projects one `[lon, lat]` position; anything outside the projectable range is dropped. */
    private fun readPosition(position: JsonArray): GeoJsonPoint? {
        if (position.size < 2) return null
        val longitude = (position[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (position[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        return project(longitude, latitude)
    }

    private fun readProperties(element: JsonElement?): Map<String, Any?> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, value) -> readValue(value) }
    }

    /**
     * One property value, as the engine's own value.
     *
     * An object or an array stays an object or an array -- upstream's worker hands `geojson-vt` the
     * parsed document and its features keep whatever the JSON held, so `["get", "rank", ["get",
     * "details"]]` works. This used to flatten both to their JSON *text*, which is what a
     * `["get"]` then read. `null` stays `null`, so `["has", …]` can tell a key that is absent from
     * one whose value is null; carrying either across the synthetic tile is
     * `GeoJsonTiler.SYNTHETIC_JSON_KEY`'s job, the wire format having no room for them.
     */
    private fun readValue(value: JsonElement): Any? = jsonToValue(value)

    /**
     * A feature's `id`, which RFC 7946 types as a string or a number and MapLibre keeps as either.
     *
     * The quoting decides: `"id": "42"` is a string id and stays one, where this used to fall back
     * to `content.toLongOrNull()` and turn it into a number. A number is a [Double], the engine's
     * only numeric type.
     */
    private fun readId(element: JsonElement?): Any? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.doubleOrNull
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

    /**
     * `[lon, lat]` to normalized Web Mercator.
     *
     * The latitude is clamped to the projection's own limit rather than allowed to run to infinity
     * at the poles; upstream's `geojson-vt` clamps the same way.
     */
    fun project(longitude: Double, latitude: Double): GeoJsonPoint {
        val x = longitude / 360.0 + 0.5
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val sinRadians = clamped * PI / 180.0
        val y = 0.5 - ln(tan(PI / 4.0 + sinRadians / 2.0)) / (2.0 * PI)
        return GeoJsonPoint(x, if (abs(y) < 1e-12) 0.0 else y.coerceIn(0.0, 1.0))
    }

    /** The latitude Web Mercator is defined up to; beyond it the projection runs to infinity. */
    const val MAX_LATITUDE = 85.0511287798
}
