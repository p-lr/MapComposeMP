package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FeatureCull] only skips what would have been clipped: an overzoomed sub-square rendered by
 * [TileRenderer], which culls, is pixel for pixel what the painter draws for every feature of the
 * ancestor with nothing skipped.
 *
 * The features are placed to probe the margin: one crossing the sub-square, one entirely outside it
 * whose stroke reaches in across the edge -- the case a cull on the bare geometry would get wrong --
 * and one far away.
 */
class FeatureCullRenderTest {

    private fun layer(styleJson: String): Layer = json.decodeFromString(Layer.serializer(), styleJson)

    private val features = listOf(
        // Crosses sub-square (1, 0) at span 2.
        Mvt.lineFeature(listOf(E / 4 to E / 4, 3 * E / 4 to E / 4)),
        // Just left of sub-square (1, 0): outside it, but a wide stroke reaches in.
        Mvt.lineFeature(listOf(E / 2 - 40 to 0, E / 2 - 40 to E / 2)),
        // Far away in the bottom-left quarter.
        Mvt.lineFeature(listOf(E / 8 to 7 * E / 8, E / 4 to 7 * E / 8)),
        // A polygon straddling the sub-square's left edge, and one wholly in the bottom-left.
        Mvt.polygonFeature(listOf(E / 2 - 200 to E / 8, E / 2 + 200 to E / 8, E / 2 + 200 to E / 4, E / 2 - 200 to E / 4)),
        Mvt.polygonFeature(listOf(0 to 3 * E / 4, E / 8 to 3 * E / 4, E / 8 to E, 0 to E)),
    )

    private val tile = Mvt.tile(Mvt.layer(name = "test", features = features))

    private suspend fun culled(style: Layer, subX: Int, subY: Int): ImageBitmap = renderToBitmap(size = SIZE) {
        TileRenderer(
            MapLibreConfiguration(style = MapLibreStyle(), tileSources = emptyMap(), spriteManager = null)
        ).render(
            canvas = this,
            tile = tile,
            styleLayer = style,
            zoom = 15.0,
            canvasSize = SIZE,
            actualZoom = 15.0,
            tileKey = "k",
            tileRef = TileRef(z = 14, x = 0, y = 0, subX = subX, subY = subY, span = 2),
        )
    }

    private suspend fun everything(style: Layer, subX: Int, subY: Int): ImageBitmap = renderToBitmap(size = SIZE) {
        translate(-subX.toFloat() * SIZE, -subY.toFloat() * SIZE) {
            for (feature in tile.layers.single().features) {
                when (style) {
                    is LineLayer -> LineLayerPainter().paint(
                        this, feature, style, SIZE * 2, E, 15.0, null, 15.0, null,
                    )
                    is FillLayer -> FillLayerPainter().paint(
                        this, feature, style, SIZE * 2, E, 15.0, null, 15.0, null,
                    )
                    else -> error("unexpected layer")
                }
            }
        }
    }

    private fun assertSamePixels(expected: ImageBitmap, actual: ImageBitmap, what: String) {
        val a = expected.toPixelMap()
        val b = actual.toPixelMap()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            assertEquals(a[x, y], b[x, y], "$what: pixel ($x, $y)")
        }
    }

    @Test
    fun `culling a line layer changes no pixel`() = runTest {
        val style = layer(
            """{"id":"line","type":"line","source":"src","source-layer":"test",
                "layout":{"line-cap":"round","line-join":"miter"},
                "paint":{"line-color":"#00aa00","line-width":12,"line-blur":2}}"""
        )
        for ((subX, subY) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
            val expected = everything(style, subX, subY)
            assertSamePixels(expected, culled(style, subX, subY), "sub-square ($subX, $subY)")
        }
        assertTrue(everything(style, 1, 0).opaquePixelCount() > 0, "the probe draws something to compare")
    }

    @Test
    fun `culling a fill layer changes no pixel`() = runTest {
        val style = layer(
            """{"id":"fill","type":"fill","source":"src","source-layer":"test",
                "paint":{"fill-color":"#aa0000","fill-outline-color":"#0000ff"}}"""
        )
        for ((subX, subY) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
            assertSamePixels(everything(style, subX, subY), culled(style, subX, subY), "sub-square ($subX, $subY)")
        }
    }

    private companion object {
        const val SIZE = 64
        const val E = Mvt.DEFAULT_EXTENT
    }
}
