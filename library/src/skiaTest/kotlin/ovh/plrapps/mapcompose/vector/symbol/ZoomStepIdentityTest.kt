package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures
import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One label, one place on the map, two zoom levels -- driven through the *real* layout pass rather
 * than through hand-built buckets.
 *
 * `CrossTileSymbolIndexUpstreamTest` pins the index and `SymbolIdentityTest` pins the hold around
 * it; neither says whether what `layoutBuckets` actually produces at two adjacent zooms is close
 * enough for the index to recognise. If it is not, every label on screen is a new symbol at each
 * zoom step: it fades in from zero while its old id is faded out on top of it, and a
 * `text-variable-anchor` label may settle somewhere else on the way -- a dip and a jump.
 */
class ZoomStepIdentityTest {

    /** Every printable codepoint a 10x12 block; enough for a `LabelArt` with a real box. */
    private fun glyphManager(): GlyphManager {
        val bytes = GlyphPbfFixtures.glyphsFile(
            GlyphPbfFixtures.fontStack(
                name = "Test Regular",
                range = "0-255",
                glyphs = (33..126).map { code ->
                    GlyphPbfFixtures.glyph(
                        id = code, width = 10, height = 12, left = 1, top = 9, advance = 8,
                        bitmap = GlyphPbfFixtures.solidBitmap(10, 12),
                    )
                },
            )
        )
        return GlyphManager(
            urlTemplate = "test://{fontstack}/{range}.pbf",
            loadResource = { _: String -> Buffer().apply { write(bytes) } as RawSource },
        )
    }

    /** Somewhere with no round binary coordinates, so no anchor lands on a tile edge. */
    private val world = Point(0.3123, 0.4171)

    private fun tileOf(z: Int, world: Point): Pair<Int, Int> {
        val tiles = 1 shl z
        return floor(world.x * tiles).toInt() to floor(world.y * tiles).toInt()
    }

    private fun mvtViewport(z: Int): MVTViewport {
        val (x, y) = tileOf(z, world)
        return MVTViewport(
            width = 512f, height = 512f, bearing = 0f, pitch = 0f,
            zoom = z.toFloat(), tileMatrix = mapOf(y to x..x),
        )
    }

    private fun viewportInfo(z: Int): ViewportInfo = ViewportInfo(
        matrix = emptyMap(),
        size = IntSize(800, 600),
        angleRad = 0f,
        pitch = 0f,
        zoom = z,
        fractionalZoom = z.toDouble(),
        centroidX = world.x,
        centroidY = world.y,
        scale = 1.0,
        fullWidth = 512 shl z,
        fullHeight = 512 shl z,
    )

    @Test
    fun `a label keeps its identity across a zoom step`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer(
            bytesFor = { z, x, y -> SymbolFixtures.tileBytesAt(world, z, x, y) },
            glyphManager = glyphManager(),
            layoutJson = """{"text-field":"A","text-font":["Test Regular"],"text-size":16}""",
        )

        val atSix = rasterizer.layoutBuckets(mvtViewport(6), z = 6.0).getOrThrow().buckets
        val labelled = atSix.filter { it.instances.isNotEmpty() }
        assertEquals(1, labelled.size, "one tile of the nine carries the point")
        rasterizer.place(atSix, viewportInfo(6), now = 0L)
        val idAtSix = labelled.single().instances.single().crossTileID
        assertTrue(idAtSix != 0L, "the label was indexed")

        val atSeven = rasterizer.layoutBuckets(mvtViewport(7), z = 7.0).getOrThrow().buckets
        val labelledAtSeven = atSeven.filter { it.instances.isNotEmpty() }
        assertEquals(1, labelledAtSeven.size)
        rasterizer.place(atSeven, viewportInfo(7), now = SYMBOL_FADE_DURATION_MS + 1)

        assertEquals(
            idAtSix,
            labelledAtSeven.single().instances.single().crossTileID,
            "the same label at the same place is the same symbol one zoom level down",
        )
    }
}
