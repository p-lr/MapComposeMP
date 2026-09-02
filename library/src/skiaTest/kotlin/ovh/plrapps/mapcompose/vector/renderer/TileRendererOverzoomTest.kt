package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.TileRef
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
 * Overzooming a vector source: the ancestor tile drawn magnified, one sub-square per map tile.
 *
 * This is maplibre-gl-js's `reparseOverscaled` path. `covering_tiles.ts` clamps the requested zoom
 * to the source's `maxzoom` for the canonical tile, and the worker re-parses that tile at the
 * *display* zoom, so the same geometry comes out magnified rather than the layer going blank.
 *
 * The two properties worth pinning are the ones the implementation trades off against each other:
 * the geometry has to grow with the span, and every style width has to *not*. That is why
 * [TileRenderer] translates the destination rather than scaling it.
 */
class TileRendererOverzoomTest {

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
        tileRef: TileRef,
        zoom: Double = 14.0,
    ): ImageBitmap = renderToBitmap(size = SIZE) {
        renderer().render(
            canvas = this,
            tile = tile,
            styleLayer = styleLayer,
            zoom = zoom,
            canvasSize = SIZE,
            actualZoom = zoom,
            tileRef = tileRef,
        )
    }

    private fun tileWith(vararg features: Tile.Feature) =
        Mvt.tile(Mvt.layer(name = "test", features = features.toList()))

    /** The number of pixels of row [y] that anything was drawn on. */
    private fun ImageBitmap.drawnOnRow(y: Int): Int =
        (0 until width).count { x -> pixelAt(x, y).alpha > 0.5f }

    @Test
    fun `a sub square of an overzoomed ancestor shows only its own quarter`() = runTest {
        /* A polygon over the ancestor's top-left quarter. At span 2 it fills the (0, 0) sub-square
         * exactly and none of the other three -- which is the whole of "the ancestor drawn twice as
         * large, cut into four map tiles". */
        val quarter = Mvt.polygonFeature(
            listOf(0 to 0, E / 2 to 0, E / 2 to E / 2, 0 to E / 2)
        )
        val style = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "paint":{"fill-color":"#ff0000","fill-antialias":false}}"""
        )

        val covered = render(style, tileWith(quarter), ref(subX = 0, subY = 0, span = 2))
        assertColorEquals(Color.Red, covered.pixelAt(SIZE / 2, SIZE / 2))
        assertEquals(SIZE * SIZE, covered.opaquePixelCount(), "the quarter fills its sub-square")

        for ((subX, subY) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val empty = render(style, tileWith(quarter), ref(subX = subX, subY = subY, span = 2))
            assertEquals(0, empty.opaquePixelCount(), "sub-square ($subX, $subY) is past the polygon")
        }
    }

    @Test
    fun `the sub squares of an ancestor tile back into the magnified whole`() = runTest {
        /* A band across the ancestor's middle half, so every sub-square has something to say. Its
         * edges land on whole pixels at both spans, which keeps the count exact rather than
         * rasterization-dependent. */
        val band = Mvt.polygonFeature(
            listOf(0 to E / 4, E to E / 4, E to 3 * E / 4, 0 to 3 * E / 4)
        )
        val style = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "paint":{"fill-color":"#0000ff","fill-antialias":false}}"""
        )

        var total = 0
        for (subY in 0..1) {
            for (subX in 0..1) {
                total += render(style, tileWith(band), ref(subX, subY, span = 2)).opaquePixelCount()
            }
        }

        /* The four sub-squares are the ancestor at twice the size, so together they cover four times
         * what one un-overzoomed tile does. */
        val whole = render(style, tileWith(band), ref(0, 0, span = 1)).opaquePixelCount()
        assertEquals(4 * whole, total, "no pixel of the ancestor is dropped or drawn twice")
    }

    @Test
    fun `a line keeps its width when the source is overzoomed`() = runTest {
        val style = layer(
            """{"id":"line","type":"line","source":"src","source-layer":"test",
                "layout":{"line-cap":"butt"},
                "paint":{"line-color":"#00ff00","line-width":8}}"""
        )
        // Vertical, at five eighths across, which puts it inside sub-square 2 of 4 at span 4.
        val line = Mvt.lineFeature(listOf(5 * E / 8 to 0, 5 * E / 8 to E))

        val plain = render(style, tileWith(line), ref(0, 0, span = 1)).drawnOnRow(SIZE / 2)
        val overzoomed = render(style, tileWith(line), ref(subX = 2, subY = 0, span = 4))
            .drawnOnRow(SIZE / 2)

        assertTrue(plain > 0, "the line has to be drawn at all")
        assertEquals(
            plain,
            overzoomed,
            "line-width is a screen width; translating the destination must not scale it",
        )
    }

    @Test
    fun `a circle outside the sub square but inside the ancestor still bleeds in`() = runTest {
        /* Upstream's rule is `CircleBucket.addFeature`: a vertex is dropped when it is outside the
         * *canonical* tile. A vertex in a neighbouring sub-square is inside the canonical tile, so
         * it is drawn and merely clipped -- reading the rule as "outside this map tile" would cut a
         * circle off at every sub-square seam. */
        val style = layer(
            """{"id":"circle","type":"circle","source":"src","source-layer":"test",
                "paint":{"circle-color":"#ff00ff","circle-radius":20}}"""
        )
        // Just left of the boundary between sub-square 0 and 1 at span 2, so its right edge is in 1.
        val nearSeam = Mvt.pointFeature((E / 2 - E / 64) to E / 2)

        val neighbour = render(style, tileWith(nearSeam), ref(subX = 1, subY = 0, span = 2))
        assertTrue(
            neighbour.opaquePixelCount() > 0,
            "the circle's own sub-square is (0, 0), but it reaches into (1, 0)",
        )
    }

    @Test
    fun `a whole tile ref renders exactly as before`() = runTest {
        val style = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "paint":{"fill-color":"#ff0000","fill-antialias":false}}"""
        )
        val covering = Mvt.polygonFeature(listOf(0 to 0, E to 0, E to E, 0 to E))

        val bitmap = render(style, tileWith(covering), ref(0, 0, span = 1))

        assertEquals(SIZE * SIZE, bitmap.opaquePixelCount())
    }

    private fun ref(subX: Int, subY: Int, span: Int) =
        TileRef(z = 14, x = 3, y = 5, subX = subX, subY = subY, span = span)

    private companion object {
        const val SIZE = 64
        const val E = Mvt.DEFAULT_EXTENT
    }
}
