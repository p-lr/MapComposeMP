package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reading a GeoJSON document into projected features. */
class GeoJsonTest {

    private fun parse(text: String) = GeoJson.parse(Json.parseToJsonElement(text))

    @Test
    fun `a feature collection yields one feature per member`() {
        val features = parse(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[10,10]},"properties":{}}
            ]}"""
        )
        assertEquals(2, features.size)
        assertTrue(features.all { it.type == Tile.GeomType.POINT })
    }

    @Test
    fun `a bare geometry is a feature too`() {
        val features = parse("""{"type":"Point","coordinates":[0,0]}""")
        assertEquals(1, features.size)
    }

    @Test
    fun `the null island projects to the middle of the world`() {
        val point = parse("""{"type":"Point","coordinates":[0,0]}""").single().rings.single().single()
        assertEquals(0.5, point.x)
        assertEquals(0.5, point.y, absoluteTolerance = 1e-12)
    }

    @Test
    fun `longitude maps linearly and latitude does not`() {
        val east = parse("""{"type":"Point","coordinates":[90,0]}""").single().rings.single().single()
        assertEquals(0.75, east.x)

        val north = parse("""{"type":"Point","coordinates":[0,45]}""").single().rings.single().single()
        assertTrue(north.y < 0.5, "north of the equator is nearer the top")
        // Web Mercator stretches towards the pole, so 45 degrees is not a quarter of the way up.
        assertTrue(north.y > 0.35, "expected the Mercator stretch, got ${north.y}")
    }

    @Test
    fun `latitude is clamped to the projection's limit`() {
        val pole = parse("""{"type":"Point","coordinates":[0,89.9]}""").single().rings.single().single()
        assertEquals(0.0, pole.y, absoluteTolerance = 1e-9)
        val south = parse("""{"type":"Point","coordinates":[0,-89.9]}""").single().rings.single().single()
        assertEquals(1.0, south.y, absoluteTolerance = 1e-9)
    }

    @Test
    fun `geometry types map onto MVT's three`() {
        assertEquals(
            Tile.GeomType.LINESTRING,
            parse("""{"type":"LineString","coordinates":[[0,0],[1,1]]}""").single().type,
        )
        assertEquals(
            Tile.GeomType.POLYGON,
            parse("""{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}""").single().type,
        )
        assertEquals(
            Tile.GeomType.POINT,
            parse("""{"type":"MultiPoint","coordinates":[[0,0],[1,1]]}""").single().type,
        )
    }

    @Test
    fun `a multipolygon flattens into one feature with every ring`() {
        val feature = parse(
            """{"type":"MultiPolygon","coordinates":[
                [[[0,0],[1,0],[1,1],[0,0]]],
                [[[5,5],[6,5],[6,6],[5,5]]]
            ]}"""
        ).single()
        assertEquals(2, feature.rings.size)
    }

    // region ring winding

    /**
     * Twice a ring's signed area, in `classify_rings.ts`'s orientation: positive is an exterior
     * ring, negative a hole.
     */
    private fun signedArea(ring: List<GeoJsonPoint>): Double {
        var sum = 0.0
        var j = ring.size - 1
        for (i in ring.indices) {
            sum += (ring[j].x - ring[i].x) * (ring[i].y + ring[j].y)
            j = i
        }
        return sum
    }

    @Test
    fun `a hole wound like its exterior is rewound`() {
        /* Both rings given clockwise in lon/lat. Nothing downstream can tell such a hole from a
         * second polygon -- `classifyRings` groups by the sign of the area -- so the hole used to be
         * filled in rather than cut out. `geojson-vt` rewinds on the way in (`src/tile.ts`). */
        val feature = parse(
            """{"type":"Polygon","coordinates":[
                [[0,0],[0,10],[10,10],[10,0],[0,0]],
                [[2,2],[2,8],[8,8],[8,2],[2,2]]
            ]}"""
        ).single()

        assertEquals(2, feature.rings.size)
        assertTrue(signedArea(feature.rings[0]) > 0.0, "the exterior ring must be positive")
        assertTrue(signedArea(feature.rings[1]) < 0.0, "the hole must be negative")
    }

    @Test
    fun `an exterior ring wound the other way is rewound too`() {
        val feature = parse(
            """{"type":"Polygon","coordinates":[
                [[0,0],[10,0],[10,10],[0,10],[0,0]],
                [[2,2],[8,2],[8,8],[2,8],[2,2]]
            ]}"""
        ).single()

        assertTrue(signedArea(feature.rings[0]) > 0.0, "the exterior ring must be positive")
        assertTrue(signedArea(feature.rings[1]) < 0.0, "the hole must be negative")
    }

    @Test
    fun `every polygon of a multipolygon is rewound on its own`() {
        // The rings are concatenated, so `classifyRings` can only find the second polygon's
        // exterior by its sign.
        val feature = parse(
            """{"type":"MultiPolygon","coordinates":[
                [[[0,0],[0,10],[10,10],[10,0],[0,0]], [[2,2],[2,8],[8,8],[8,2],[2,2]]],
                [[[20,0],[30,0],[30,10],[20,10],[20,0]], [[22,2],[28,2],[28,8],[22,8],[22,2]]]
            ]}"""
        ).single()

        assertEquals(4, feature.rings.size)
        assertTrue(signedArea(feature.rings[0]) > 0.0)
        assertTrue(signedArea(feature.rings[1]) < 0.0)
        assertTrue(signedArea(feature.rings[2]) > 0.0)
        assertTrue(signedArea(feature.rings[3]) < 0.0)
    }

    // endregion

    @Test
    fun `a geometry collection becomes several features sharing the properties`() {
        val features = parse(
            """{"type":"Feature","properties":{"name":"x"},"geometry":{
                "type":"GeometryCollection","geometries":[
                  {"type":"Point","coordinates":[0,0]},
                  {"type":"LineString","coordinates":[[0,0],[1,1]]}
                ]}}"""
        )
        assertEquals(2, features.size)
        assertTrue(features.all { it.properties["name"] == "x" })
    }

    @Test
    fun `properties keep their json types`() {
        val feature = parse(
            """{"type":"Feature","properties":{"s":"a","n":2,"b":true,"z":null},
               "geometry":{"type":"Point","coordinates":[0,0]}}"""
        ).single()
        assertEquals("a", feature.properties["s"])
        assertEquals(2.0, feature.properties["n"])
        assertEquals(true, feature.properties["b"])
        assertEquals(null, feature.properties["z"])
    }

    @Test
    fun `a feature id is read`() {
        val feature = parse(
            """{"type":"Feature","id":42,"properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""
        ).single()
        assertEquals(42.0, feature.id)
    }

    @Test
    fun `a string feature id stays a string`() {
        val feature = parse(
            """{"type":"Feature","id":"abc","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""
        ).single()
        assertEquals("abc", feature.id)
    }

    /* RFC 7946 types an id as a string or a number, and the quoting is what decides which. This
     * used to fall back to `content.toLongOrNull()` and read a quoted "42" as the number 42. */
    @Test
    fun `a quoted numeric feature id stays a string`() {
        val feature = parse(
            """{"type":"Feature","id":"42","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""
        ).single()
        assertEquals("42", feature.id)
    }

    @Test
    fun `a feature with no id has none`() {
        val feature = parse(
            """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}"""
        ).single()
        assertEquals(null, feature.id)
    }

    @Test
    fun `a malformed feature is skipped rather than failing the document`() {
        val features = parse(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Nonsense","coordinates":[0,0]},"properties":{}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}}
            ]}"""
        )
        assertEquals(1, features.size)
    }

    @Test
    fun `a degenerate geometry is dropped`() {
        assertTrue(parse("""{"type":"LineString","coordinates":[[0,0]]}""").isEmpty())
        assertTrue(parse("""{"type":"Polygon","coordinates":[[[0,0],[1,1]]]}""").isEmpty())
        assertTrue(GeoJson.parse(null).isEmpty())
    }

    // region generateId

    private fun generated(text: String) =
        GeoJson.parse(Json.parseToJsonElement(text), generateId = true).map { it.id }

    @Test
    fun `generateId is the position in the collection`() {
        val ids = generated(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},"properties":{}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[2,2]},"properties":{}}
            ]}"""
        )
        assertEquals(listOf(0.0, 1.0, 2.0), ids)
    }

    /** `convert` calls `convertFeature` with no index for a bare feature, and `index || 0` is 0. */
    @Test
    fun `generateId gives a bare feature zero`() {
        assertEquals(
            listOf(0.0),
            generated("""{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}}""")
        )
    }

    /** `let id = geojson.id; ... else if (options.generateId) id = index || 0` -- it replaces. */
    @Test
    fun `generateId replaces an id the document wrote`() {
        assertEquals(
            listOf(0.0),
            generated(
                """{"type":"Feature","id":"keep me","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}}"""
            )
        )
    }

    /**
     * A `GeometryCollection` is one entry of the collection, so every geometry it holds shares that
     * entry's index -- upstream's `convertFeature` recurses with the index it was given.
     */
    @Test
    fun `every member of a geometry collection shares the entry's index`() {
        val ids = generated(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}},
              {"type":"Feature","properties":{},"geometry":{"type":"GeometryCollection","geometries":[
                {"type":"Point","coordinates":[1,1]},
                {"type":"Point","coordinates":[2,2]}
              ]}}
            ]}"""
        )
        assertEquals(listOf(0.0, 1.0, 1.0), ids)
    }

    // endregion
}
