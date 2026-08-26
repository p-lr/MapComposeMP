package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
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
    ): ImageBitmap = renderToBitmap(size = SIZE) {
        renderer.render(
            canvas = this,
            tile = tile,
            styleLayer = styleLayer,
            zoom = zoom,
            canvasSize = SIZE,
            actualZoom = zoom,
            tileKey = tileKey,
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
            """{"id":"hm","type":"heatmap","source":"src","source-layer":"test"}""",
            """{"id":"fe","type":"fill-extrusion","source":"src","source-layer":"test"}""",
            """{"id":"sky","type":"sky"}""",
            """{"id":"s","type":"symbol","source":"src","source-layer":"test"}""",
        )

        for (styleJson in unpainted) {
            val bitmap = render(layer(styleJson), tileWith(coveringPolygon()))
            assertEquals(0, bitmap.opaquePixelCount(), "layer $styleJson should draw nothing")
        }
    }

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
}
