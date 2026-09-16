package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import ovh.plrapps.mapcompose.vector.spec.Tile
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

    fun parse(element: JsonElement?): List<GeoJsonFeature> {
        val out = mutableListOf<GeoJsonFeature>()
        readInto(element, out, properties = emptyMap(), id = null)
        return out
    }

    private fun readInto(
        element: JsonElement?,
        out: MutableList<GeoJsonFeature>,
        properties: Map<String, Any?>,
        id: Any?,
    ) {
        val obj = element as? JsonObject ?: return
        when ((obj["type"] as? JsonPrimitive)?.contentOrNull()) {
            "FeatureCollection" -> {
                val features = obj["features"] as? JsonArray ?: return
                for (feature in features) readInto(feature, out, properties, id)
            }

            "Feature" -> {
                val ownProperties = readProperties(obj["properties"])
                readInto(obj["geometry"], out, ownProperties, readId(obj["id"]))
            }

            "GeometryCollection" -> {
                val geometries = obj["geometries"] as? JsonArray ?: return
                for (geometry in geometries) readInto(geometry, out, properties, id)
            }

            else -> readGeometry(obj, properties, id)?.let { out += it }
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

    private fun readPolygon(coordinates: JsonArray): List<List<GeoJsonPoint>> =
        coordinates.mapNotNull { ring ->
            (ring as? JsonArray)?.let { readLine(it) }?.takeIf { it.size >= 3 }
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

    private fun readValue(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.doubleOrNull != null -> value.doubleOrNull
            else -> value.content
        }

        else -> value.toString()
    }

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
