package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.renderer.GeometryDecoders
import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cutting a GeoJSON document into MVT-shaped tiles.
 *
 * The output is read back with the same [GeometryDecoders] the painters use, so a test asserts on
 * what a painter would actually see rather than on the command stream.
 */
class GeoJsonTilerTest {

    private val decoders = GeometryDecoders()

    private fun tiler(text: String, tolerance: Double = GeoJsonTiler.DEFAULT_TOLERANCE) =
        GeoJsonTiler(GeoJson.parse(Json.parseToJsonElement(text)), tolerance = tolerance)

    private fun Tile.layer(): Tile.Layer = layers.single()

    private fun Tile.Feature.vertices(canvasSize: Int = 4096) =
        decoders.decodeVertices(geometry, extent = 4096, canvasSize = canvasSize)

    /**
     * Every vertex the tile carries, across every feature.
     *
     * A document that reaches the antimeridian is cut from the neighbouring world copies too, so a
     * tile near the edge legitimately carries the same feature twice -- once in its own right and
     * once in the buffer, wrapped. See the world-wrap tests below.
     */
    private fun Tile.allVertices() = layer().features.flatMap { it.vertices() }

    // region feature ids

    /**
     * `Tile.Feature.id` is a protobuf `uint64` and `spec/vector_tile.kt` is pbandk-generated, so a
     * string id -- which RFC 7946 allows and MapLibre keeps -- rides the layer's own tag table
     * instead. `BaseRenderer.buildEvalFeature` lifts it back out and removes it, so it never
     * reaches `["get"]`; here the tile is inspected directly.
     */
    @Test
    fun `a string feature id crosses the synthetic tile`() {
        val tile = assertNotNull(
            tiler("""{"type":"Feature","id":"abc","properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}""")
                .tile(0, 0, 0)
        )
        val layer = tile.layer()
        val feature = layer.features.single()
        assertNull(feature.id, "the wire format cannot hold a string id")
        assertEquals("abc", layer.tagValue(feature, GeoJsonTiler.SYNTHETIC_ID_KEY))
    }

    @Test
    fun `an integral feature id is carried both ways so the tile stays self-describing`() {
        val tile = assertNotNull(
            tiler("""{"type":"Feature","id":42,"properties":{},"geometry":{"type":"Point","coordinates":[0,0]}}""")
                .tile(0, 0, 0)
        )
        val layer = tile.layer()
        val feature = layer.features.single()
        assertEquals(42L, feature.id)
        assertEquals(42.0, layer.tagValue(feature, GeoJsonTiler.SYNTHETIC_ID_KEY))
    }

    @Test
    fun `a feature with no id carries no synthetic tag`() {
        val tile = assertNotNull(
            tiler("""{"type":"Feature","properties":{"a":1},"geometry":{"type":"Point","coordinates":[0,0]}}""")
                .tile(0, 0, 0)
        )
        val layer = tile.layer()
        assertNull(layer.tagValue(layer.features.single(), GeoJsonTiler.SYNTHETIC_ID_KEY))
        assertTrue(GeoJsonTiler.SYNTHETIC_ID_KEY !in layer.keys)
    }

    /** The value a feature carries under [key], read through the layer's shared tables. */
    private fun Tile.Layer.tagValue(feature: Tile.Feature, key: String): Any? {
        for (i in feature.tags.indices step 2) {
            if (keys.getOrNull(feature.tags[i]) != key) continue
            val value = values.getOrNull(feature.tags[i + 1]) ?: return null
            return value.stringValue ?: value.doubleValue ?: value.intValue ?: value.boolValue
        }
        return null
    }

    // endregion

    @Test
    fun `the zero tile covers the whole world`() {
        val tile = assertNotNull(tiler("""{"type":"Point","coordinates":[0,0]}""").tile(0, 0, 0))
        val point = tile.layer().features.single().vertices().single()
        // The null island is the middle of the world, so the middle of the zero tile.
        assertEquals(2048.0, point.x, absoluteTolerance = 1.0)
        assertEquals(2048.0, point.y, absoluteTolerance = 1.0)
    }

    @Test
    fun `a point lands in the tile that contains it`() {
        val document = """{"type":"Point","coordinates":[0,0]}"""
        // At zoom 1 the null island is the corner shared by all four tiles.
        assertNotNull(tiler(document).tile(1, 0, 0))
        // ...and nowhere near the far side of the world.
        assertNull(tiler("""{"type":"Point","coordinates":[-170,80]}""").tile(1, 1, 1))
    }

    @Test
    fun `the layer carries the name upstream uses`() {
        val tile = assertNotNull(tiler("""{"type":"Point","coordinates":[0,0]}""").tile(0, 0, 0))
        assertEquals(GeoJsonTiler.LAYER_NAME, tile.layer().name)
        assertEquals(GeoJsonTiler.DEFAULT_EXTENT, tile.layer().extent)
    }

    @Test
    fun `properties become the layer's shared key and value tables`() {
        val tile = assertNotNull(
            tiler(
                """{"type":"Feature","properties":{"name":"here","rank":3},
                   "geometry":{"type":"Point","coordinates":[0,0]}}"""
            ).tile(0, 0, 0)
        )
        val layer = tile.layer()
        assertEquals(listOf("name", "rank"), layer.keys)
        assertEquals("here", layer.values[0].stringValue)
        assertEquals(3.0, layer.values[1].doubleValue)
        // Tags index into those tables pairwise.
        assertEquals(listOf(0, 0, 1, 1), layer.features.single().tags)
    }

    @Test
    fun `two features sharing a value share its table entry`() {
        val tile = assertNotNull(
            tiler(
                """{"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"k":"v"},"geometry":{"type":"Point","coordinates":[0,0]}},
                  {"type":"Feature","properties":{"k":"v"},"geometry":{"type":"Point","coordinates":[1,1]}}
                ]}"""
            ).tile(0, 0, 0)
        )
        assertEquals(1, tile.layer().keys.size)
        assertEquals(1, tile.layer().values.size)
    }

    @Test
    fun `an empty tile is null rather than an empty layer`() {
        assertNull(tiler("""{"type":"Point","coordinates":[0,0]}""").tile(4, 0, 0))
        assertNull(GeoJsonTiler(emptyList()).tile(0, 0, 0))
    }

    @Test
    fun `a line crossing the tile is clipped to it with a buffer`() {
        // A line right across the equator: at zoom 1 the western tile keeps only its half.
        val tile = assertNotNull(
            tiler("""{"type":"LineString","coordinates":[[-180,0],[180,0]]}""").tile(1, 0, 0)
        )
        val vertices = tile.allVertices()
        assertTrue(vertices.all { it.x >= -GeoJsonTiler.DEFAULT_BUFFER - 1 })
        assertTrue(vertices.all { it.x <= 4096 + GeoJsonTiler.DEFAULT_BUFFER + 1 })
        // ...and it does reach both edges of it.
        assertTrue(vertices.minOf { it.x } < 1.0)
        assertTrue(vertices.maxOf { it.x } > 4095.0)
    }

    @Test
    fun `a line that leaves and returns becomes two pieces`() {
        // The western tile at zoom 1 spans longitudes -180 to 0: this line runs out of its eastern
        // edge, doubles back well outside it, and comes home again.
        val tile = assertNotNull(
            tiler(
                """{"type":"LineString","coordinates":[
                    [-90,10],[90,10],[90,30],[-90,30]]}"""
            ).tile(1, 0, 0)
        )
        val geometry = tile.layer().features.single().geometry
        // Two MoveTo commands, i.e. two separate pieces.
        val moveTos = countMoveTo(geometry)
        assertEquals(2, moveTos, "expected the line to be cut in two, got $moveTos pieces")
    }

    @Test
    fun `a polygon stays closed when it is clipped`() {
        val tile = assertNotNull(
            tiler(
                """{"type":"Polygon","coordinates":[[[-180,-80],[180,-80],[180,80],[-180,80],[-180,-80]]]}"""
            ).tile(1, 0, 0)
        )
        // The polygon spans the whole world, so the tile carries its wrapped copy as well; every
        // copy has to come out of the clip as a closed ring.
        for (feature in tile.layer().features) {
            assertEquals(Tile.GeomType.POLYGON, feature.type)
            val rings = decoders.decodePolygons(feature.geometry, extent = 4096, canvasSize = 4096)
            assertTrue(rings.isNotEmpty())
            assertTrue(rings.single().single().size >= 4, "a clipped ring must still enclose an area")
        }
    }

    @Test
    fun `simplification drops vertices that add nothing`() {
        // Twenty collinear points. Kept well clear of the antimeridian: a document that reaches it
        // is cut from the neighbouring world copies too, and this is about simplification alone.
        val coordinates = (0..20).joinToString(",") { "[${-80.0 + it * 8.0},0]" }
        val detailed = tiler("""{"type":"LineString","coordinates":[$coordinates]}""", tolerance = 0.0)
            .tile(0, 0, 0)!!.layer().features.single().vertices().size
        val simplified = tiler("""{"type":"LineString","coordinates":[$coordinates]}""")
            .tile(0, 0, 0)!!.layer().features.single().vertices().size
        assertTrue(simplified < detailed, "expected fewer than $detailed vertices, got $simplified")
        // The ends are never dropped, so a straight line collapses to exactly two.
        assertEquals(2, simplified)
    }

    @Test
    fun `simplification keeps a corner`() {
        val tile = tiler(
            """{"type":"LineString","coordinates":[[-90,0],[0,0],[0,60]]}"""
        ).tile(0, 0, 0)!!
        assertEquals(3, tile.layer().features.single().vertices().size)
    }

    @Test
    fun `a multipoint keeps every point`() {
        val tile = assertNotNull(
            tiler("""{"type":"MultiPoint","coordinates":[[-10,0],[0,0],[10,0]]}""").tile(0, 0, 0)
        )
        assertEquals(3, tile.layer().features.single().vertices().size)
    }

    @Test
    fun `a source honours its zoom range`() {
        val source = GeoJsonSource(
            features = GeoJson.parse(Json.parseToJsonElement("""{"type":"Point","coordinates":[0,0]}""")),
            minZoom = 2,
            maxZoom = 4,
        )
        assertNull(source.resolve(1, 1, 1))
        assertEquals(TileRef.whole(z = 2, x = 2, y = 2), source.resolve(2, 2, 2))
        assertNotNull(source.tile(2, 2, 2))
    }

    @Test
    fun `a source above its maxzoom is overzoomed onto its deepest ancestor`() {
        val source = GeoJsonSource(
            features = GeoJson.parse(Json.parseToJsonElement("""{"type":"Point","coordinates":[0,0]}""")),
            minZoom = 2,
            maxZoom = 4,
        )
        // geojson-vt stops cutting at maxzoom, so z6 reads the z4 tile that contains it.
        assertEquals(
            TileRef(z = 4, x = 8, y = 8, subX = 1, subY = 2, span = 4),
            source.resolve(6, 33, 34),
        )
        // The point at (0, 0) lands in the z4 tile (8, 8), so the overzoomed tile carries it.
        assertNotNull(source.tile(6, 33, 34))
    }

    // region world wrap
    //
    // `wrap.ts`: a coordinate past the antimeridian projects outside `[0, 1]` and so falls in no
    // tile at all unless the document is offered at the neighbouring world copies too.

    @Test
    fun `a point past the antimeridian is wrapped into the world`() {
        // Longitude 181 projects to x = 1.00278, which is no tile's; wrapped it is x = 0.00278.
        val tile = tiler("""{"type":"Point","coordinates":[181,0]}""").tile(0, 0, 0)

        assertNotNull(tile)
        val xs = tile.allVertices().map { it.x }
        assertTrue(
            xs.any { it in 5.0..20.0 },
            "the wrapped point should sit just east of the prime meridian, but got $xs",
        )
    }

    @Test
    fun `a line crossing the antimeridian keeps both halves`() {
        val tile = tiler("""{"type":"LineString","coordinates":[[179,0],[181,0]]}""").tile(0, 0, 0)

        assertNotNull(tile)
        val xs = tile.layer().features.flatMap { it.vertices() }.map { it.x }
        assertTrue(
            xs.any { it < 100.0 },
            "the half past the antimeridian should reappear at the western edge, but got $xs",
        )
        assertTrue(
            xs.any { it > 4000.0 },
            "the half before the antimeridian should stay where it is, but got $xs",
        )
    }

    @Test
    fun `a document inside the world is not duplicated`() {
        // The two copies a wrap adds must not reach a tile they do not touch.
        val tile = tiler("""{"type":"Point","coordinates":[0,0]}""").tile(0, 0, 0)

        assertNotNull(tile)
        assertEquals(1, tile.layer().features.size)
    }

    // endregion

    private fun countMoveTo(geometry: List<Int>): Int {
        var count = 0
        var i = 0
        while (i < geometry.size) {
            val command = geometry[i] and 0x7
            val n = geometry[i] shr 3
            i++
            when (command) {
                1, 2 -> {
                    if (command == 1) count++
                    i += 2 * n
                }

                else -> Unit
            }
        }
        return count
    }
}
