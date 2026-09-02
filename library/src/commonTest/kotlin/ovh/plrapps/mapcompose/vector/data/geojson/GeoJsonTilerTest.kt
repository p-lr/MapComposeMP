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
        val vertices = tile.layer().features.single().vertices()
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
        val feature = tile.layer().features.single()
        assertEquals(Tile.GeomType.POLYGON, feature.type)
        val rings = decoders.decodePolygons(feature.geometry, extent = 4096, canvasSize = 4096)
        assertTrue(rings.isNotEmpty())
        assertTrue(rings.single().single().size >= 4, "a clipped ring must still enclose an area")
    }

    @Test
    fun `simplification drops vertices that add nothing`() {
        // Twenty collinear points across the world.
        val coordinates = (0..20).joinToString(",") { "[${-180.0 + it * 18.0},0]" }
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
