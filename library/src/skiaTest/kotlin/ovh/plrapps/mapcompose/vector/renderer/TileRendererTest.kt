package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJson
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonTiler
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the per-layer gating [TileRenderer] does before any painter runs: visibility, zoom range,
 * source-layer selection, filters, and `*-sort-key` ordering.
 *
 * Layers are parsed from style JSON rather than constructed, so filters and sort keys go through
 * the real serializers and expression compiler.
 */
class TileRendererTest {

    private fun renderer() = TileRenderer(
        configuration = MapLibreConfiguration(
            style = MapLibreStyle(),
            tileSources = emptyMap(),
            spriteManager = null,
        ),
        pathCache = LruCache(16),
        pathCacheMutex = Mutex(),
        localPropCache = HashMap<String, EvalFeature>(),
    )

    private fun layer(styleJson: String): Layer = json.decodeFromString(Layer.serializer(), styleJson)

    private suspend fun render(
        styleLayer: Layer,
        tile: Tile?,
        zoom: Double = 10.0,
        tileKey: String? = null,
        renderer: TileRenderer = renderer(),
        neighbours: List<NeighbourTile> = emptyList(),
    ): ImageBitmap = renderToBitmap(size = SIZE) {
        renderer.render(
            canvas = this,
            tile = tile,
            styleLayer = styleLayer,
            zoom = zoom,
            canvasSize = SIZE,
            actualZoom = zoom,
            tileKey = tileKey,
            neighbours = neighbours,
        )
    }

    private fun tileWith(vararg features: Tile.Feature, layerName: String = "test") =
        Mvt.tile(Mvt.layer(name = layerName, features = features.toList()))

    private fun fullTileFill(color: String, extra: String = "") = layer(
        """{"id":"fill","type":"fill","source":"src","source-layer":"test",
            "paint":{"fill-color":"$color","fill-antialias":false}$extra}"""
    )

    @Test
    fun `a layer draws its source-layer features`() = runTest {
        val bitmap = render(fullTileFill("#ff0000"), tileWith(coveringPolygon()))

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `visibility none suppresses the layer`() = runTest {
        val styleLayer = fullTileFill("#ff0000", extra = ""","layout":{"visibility":"none"}""")

        val bitmap = render(styleLayer, tileWith(coveringPolygon()))

        assertEquals(0, bitmap.opaquePixelCount(), "a hidden layer must draw nothing")
    }

    @Test
    fun `visibility visible is drawn`() = runTest {
        val styleLayer = fullTileFill("#ff0000", extra = ""","layout":{"visibility":"visible"}""")

        val bitmap = render(styleLayer, tileWith(coveringPolygon()))

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `minzoom and maxzoom bound the layer`() = runTest {
        val styleLayer = fullTileFill("#ff0000", extra = ""","minzoom":8,"maxzoom":12""")

        suspend fun pixelsAt(zoom: Double) =
            render(styleLayer, tileWith(coveringPolygon()), zoom = zoom).opaquePixelCount()

        assertEquals(0, pixelsAt(7.0), "below minzoom")
        assertTrue(pixelsAt(8.0) > 0, "at minzoom")
        assertTrue(pixelsAt(11.0) > 0, "inside")
        assertEquals(0, pixelsAt(12.0), "at maxzoom")
    }

    @Test
    fun `a missing source-layer draws nothing`() = runTest {
        val bitmap = render(fullTileFill("#ff0000"), tileWith(coveringPolygon(), layerName = "other"))

        assertEquals(0, bitmap.opaquePixelCount())
    }

    @Test
    fun `a null tile draws nothing`() = runTest {
        assertEquals(0, render(fullTileFill("#ff0000"), tile = null).opaquePixelCount())
    }

    @Test
    fun `a filter selects which features are drawn`() = runTest {
        val styleLayer = fullTileFill("#ff0000", extra = ""","filter":["==",["get","kind"],"keep"]""")
        val tileLayer = Mvt.layer(
            name = "test",
            features = listOf(
                Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 2048, 4096), id = 1, tags = listOf(0, 0)),
                Mvt.polygonFeature(Mvt.clockwiseRing(2048, 0, 4096, 4096), id = 2, tags = listOf(0, 1)),
            ),
            keys = listOf("kind"),
            values = listOf(Mvt.stringValue("keep"), Mvt.stringValue("drop")),
        )

        val bitmap = render(styleLayer, Mvt.tile(tileLayer))

        assertColorEquals(Color.Red, bitmap.pixelAt(16, 32), message = "the matching feature")
        assertEquals(0f, bitmap.pixelAt(48, 32).alpha, "the filtered-out feature")
    }

    @Test
    fun `fill-sort-key decides which overlapping feature ends up on top`() = runTest {
        // Two features covering the same pixel; the higher sort key must win regardless of the
        // order they appear in the tile.
        val tileLayer = Mvt.layer(
            name = "test",
            features = listOf(
                Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096), id = 1, tags = listOf(0, 0)),
                Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096), id = 2, tags = listOf(0, 1)),
            ),
            keys = listOf("rank"),
            values = listOf(Mvt.numberValue(2.0), Mvt.numberValue(1.0)),
        )
        val styleLayer = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "layout":{"fill-sort-key":["get","rank"]},
                "paint":{"fill-color":["match",["get","rank"],2,"#ff0000","#0000ff"],"fill-antialias":false}}"""
        )

        val bitmap = render(styleLayer, Mvt.tile(tileLayer))

        assertColorEquals(
            Color.Red,
            bitmap.pixelAt(32, 32),
            message = "rank 2 sorts after rank 1 and so draws on top"
        )
    }

    @Test
    fun `without a sort key features keep their order in the tile`() = runTest {
        val tileLayer = Mvt.layer(
            name = "test",
            features = listOf(
                Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096), id = 1, tags = listOf(0, 0)),
                Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096), id = 2, tags = listOf(0, 1)),
            ),
            keys = listOf("rank"),
            values = listOf(Mvt.numberValue(2.0), Mvt.numberValue(1.0)),
        )
        val styleLayer = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "paint":{"fill-color":["match",["get","rank"],2,"#ff0000","#0000ff"],"fill-antialias":false}}"""
        )

        val bitmap = render(styleLayer, Mvt.tile(tileLayer))

        assertColorEquals(Color.Blue, bitmap.pixelAt(32, 32), message = "the last feature in the tile wins")
    }

    @Test
    fun `a background layer covers the tile without a source`() = runTest {
        val styleLayer = layer("""{"id":"bg","type":"background","paint":{"background-color":"#00ff00"}}""")

        val bitmap = render(styleLayer, tile = null)

        assertColorEquals(Color.Green, bitmap.pixelAt(0, 0))
        assertColorEquals(Color.Green, bitmap.pixelAt(SIZE - 1, SIZE - 1))
    }

    @Test
    fun `layer types with no painter are skipped without throwing`() = runTest {
        val unpainted = listOf(
            """{"id":"r","type":"raster","source":"src","source-layer":"test"}""",
            """{"id":"h","type":"hillshade","source":"src","source-layer":"test"}""",
            """{"id":"fe","type":"fill-extrusion","source":"src","source-layer":"test"}""",
            """{"id":"sky","type":"sky"}""",
            """{"id":"s","type":"symbol","source":"src","source-layer":"test"}""",
        )

        for (styleJson in unpainted) {
            val bitmap = render(layer(styleJson), tileWith(coveringPolygon()))
            assertEquals(0, bitmap.opaquePixelCount(), "layer $styleJson should draw nothing")
        }
    }

    // region heatmap
    //
    // A heatmap's kernels reach past the tile their point belongs to, so `render` is given the
    // neighbouring tiles as well; these cover the gathering, not the kernel itself, which
    // `HeatmapLayerPainterTest` and `HeatmapKernelTest` cover.

    private fun heatmapLayer(extra: String = "") = layer(
        """{"id":"heat","type":"heatmap","source":"src","source-layer":"points",
            "paint":{"heatmap-color":["interpolate",["linear"],["heatmap-density"],
                0,"rgba(255, 0, 0, 0)",1,"rgba(255, 0, 0, 1)"]}$extra}"""
    )

    private fun pointTile(vararg points: Pair<Int, Int>, tags: List<Int> = emptyList()) = Mvt.tile(
        Mvt.layer(name = "points", features = listOf(Mvt.pointFeature(*points, tags = tags)))
    )

    @Test
    fun `a heatmap layer accumulates its own tile's points`() = runTest {
        val bitmap = render(heatmapLayer(), pointTile(2048 to 2048))

        assertTrue(bitmap.pixelAt(32, 32).alpha > 0.2f, "the point must heat the tile centre")
    }

    @Test
    fun `a heatmap layer accumulates the neighbouring tiles' points`() = runTest {
        // The point sits just inside the western neighbour's eastern edge, so its kernel reaches
        // into this tile even though this tile carries no points at all.
        val west = NeighbourTile(tile = pointTile(4032 to 2048), dx = -1, dy = 0)

        val bitmap = render(heatmapLayer(), tile = null, neighbours = listOf(west))

        assertTrue(bitmap.pixelAt(0, 32).alpha > 0.1f, "the west edge must be heated from outside")
        assertEquals(0f, bitmap.pixelAt(SIZE - 1, 32).alpha, "the east edge is out of reach")
    }

    @Test
    fun `a point outside its own tile is dropped rather than counted twice`() = runTest {
        // 4096 + 128 puts the point in the western neighbour's *buffer*: it belongs to this tile,
        // which carries it too, and upstream's CircleBucket drops it from the neighbour's bucket.
        val west = NeighbourTile(tile = pointTile(4224 to 2048), dx = -1, dy = 0)

        val bitmap = render(heatmapLayer(), tile = null, neighbours = listOf(west))

        assertEquals(0, bitmap.opaquePixelCount(), "a buffered point must not be accumulated twice")
    }

    @Test
    fun `a filter selects which points heat the tile`() = runTest {
        val styleLayer = heatmapLayer(extra = ""","filter":["==",["get","kind"],"keep"]""")
        val tile = Mvt.tile(
            Mvt.layer(
                name = "points",
                features = listOf(
                    Mvt.pointFeature(1024 to 2048, id = 1, tags = listOf(0, 0)),
                    Mvt.pointFeature(3072 to 2048, id = 2, tags = listOf(0, 1)),
                ),
                keys = listOf("kind"),
                values = listOf(Mvt.stringValue("keep"), Mvt.stringValue("drop")),
            )
        )

        val bitmap = render(styleLayer, tile)

        assertTrue(bitmap.pixelAt(16, 32).alpha > 0.2f, "the matching point")
        assertEquals(0f, bitmap.pixelAt(63, 32).alpha, "the filtered-out point")
    }

    @Test
    fun `a heatmap layer ignores non-point features`() = runTest {
        val tile = Mvt.tile(
            Mvt.layer(name = "points", features = listOf(coveringPolygon()))
        )

        assertEquals(0, render(heatmapLayer(), tile).opaquePixelCount())
    }

    // endregion

    // region circle boundaries
    //
    // A disc reaches past the tile its centre belongs to, and this tile's bitmap is the clip, so the
    // neighbouring tiles are gathered for a circle layer too. See `CircleVertexGate`.

    private fun circleLayer(extra: String = "") = layer(
        """{"id":"dots","type":"circle","source":"src","source-layer":"points",
            "paint":{"circle-radius":8,"circle-color":"#ff0000"}$extra}"""
    )

    @Test
    fun `a circle owned by a neighbouring tile is drawn into this one`() = runTest {
        // 4032 of 4096 is one canvas pixel inside the western neighbour's eastern edge -- far
        // outside the range any MVT buffer would duplicate into this tile, and the disc still
        // reaches 7 pixels in.
        val west = NeighbourTile(tile = pointTile(4032 to 2048), dx = -1, dy = 0)

        val bitmap = render(circleLayer(), tile = null, neighbours = listOf(west))

        assertColorEquals(Color.Red, bitmap.pixelAt(2, 32))
        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "the disc must not reach the tile centre")
    }

    @Test
    fun `a circle out of reach of this tile is not drawn`() = runTest {
        // Ten canvas pixels into the western neighbour, against a radius of eight.
        val west = NeighbourTile(tile = pointTile(3456 to 2048), dx = -1, dy = 0)

        val bitmap = render(circleLayer(), tile = null, neighbours = listOf(west))

        assertEquals(0, bitmap.opaquePixelCount())
    }

    @Test
    fun `a buffered copy is not drawn once its own tile is gathered`() = runTest {
        /* The same vertex twice: owned by the western neighbour at 4032, and duplicated into this
         * tile's buffer at -64. Drawing both would double a translucent circle's alpha along the
         * seam, which is what the ownership rule prevents. */
        val west = NeighbourTile(tile = pointTile(4032 to 2048), dx = -1, dy = 0)
        val translucent = layer(
            """{"id":"dots","type":"circle","source":"src","source-layer":"points",
                "paint":{"circle-radius":8,"circle-color":"#ff0000","circle-opacity":0.5}}"""
        )

        val both = render(translucent, pointTile(-64 to 2048), neighbours = listOf(west))
        val once = render(translucent, tile = null, neighbours = listOf(west))

        assertEquals(
            once.pixelAt(2, 32).alpha,
            both.pixelAt(2, 32).alpha,
            "the vertex must be drawn by its owner only",
        )
    }

    @Test
    fun `a buffered copy is drawn when its own tile was not gathered`() = runTest {
        // No neighbours: the buffered copy is all this tile has, and dropping it would leave the
        // disc's half missing entirely -- the behaviour a failed neighbour fetch falls back to.
        val bitmap = render(circleLayer(), pointTile(-64 to 2048))

        assertColorEquals(Color.Red, bitmap.pixelAt(2, 32))
    }

    // endregion

    @Test
    fun `a second render of the same tile hits the geometry cache and looks identical`() = runTest {
        // With a tileKey the painters memoise decoded geometry; a cache hit must not change what is
        // drawn, and must not mix a fill layer's cached Path up with a line layer's polylines.
        val shared = renderer()
        val fill = fullTileFill("#ff0000")
        val line = layer(
            """{"id":"line","type":"line","source":"src","source-layer":"test",
                "paint":{"line-color":"#0000ff","line-width":6}}"""
        )
        val tile = tileWith(coveringPolygon())

        val firstFill = render(fill, tile, tileKey = "src-10-0-0", renderer = shared)
        val firstLine = render(line, tile, tileKey = "src-10-0-0", renderer = shared)
        val secondFill = render(fill, tile, tileKey = "src-10-0-0", renderer = shared)
        val secondLine = render(line, tile, tileKey = "src-10-0-0", renderer = shared)

        assertEquals(firstFill.opaquePixelCount(), secondFill.opaquePixelCount())
        assertEquals(firstLine.opaquePixelCount(), secondLine.opaquePixelCount())
        assertColorEquals(Color.Red, secondFill.pixelAt(32, 32))
        assertColorEquals(Color.Blue, secondLine.pixelAt(0, 32), message = "the ring's left edge")
    }

    private fun coveringPolygon() = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096))

    private companion object {
        const val SIZE = 64
    }

    @Test
    fun `a layer without a source-layer takes the tile's only layer`() = runTest {
        // A geojson source has no `source-layer` -- the spec forbids one, because the document is a
        // single layer -- and its tiles carry the name upstream's GeoJSONWrapper uses.
        val styleLayer = layer(
            """{"id":"fill","type":"fill","source":"geo",
                "paint":{"fill-color":"#ff0000","fill-antialias":false}}"""
        )
        val tile = tileWith(coveringPolygon(), layerName = GeoJsonTiler.LAYER_NAME)

        assertColorEquals(Color.Red, render(styleLayer, tile).pixelAt(32, 32))
    }

    @Test
    fun `a geojson document renders like a vector tile`() = runTest {
        val features = GeoJson.parse(
            Json.parseToJsonElement(
                """{"type":"Polygon","coordinates":[[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]]]}"""
            )
        )
        val tile = assertNotNull(GeoJsonTiler(features).tile(0, 0, 0))
        val styleLayer = layer(
            """{"id":"fill","type":"fill","source":"geo",
                "paint":{"fill-color":"#00ff00","fill-antialias":false}}"""
        )

        assertColorEquals(Color.Green, render(styleLayer, tile).pixelAt(32, 32))
    }
}
