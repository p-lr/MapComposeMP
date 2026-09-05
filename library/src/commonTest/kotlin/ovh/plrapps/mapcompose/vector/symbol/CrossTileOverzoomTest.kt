package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import ovh.plrapps.mapcompose.vector.renderer.Point
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-tile identity when the source is *overzoomed*, which is the case every style in the wild
 * reaches: `test_style_bright.json` stops at `maxzoom` 14 and `test_style_street_v2.json` at 15, so
 * every zoom past that is drawn from a magnified ancestor.
 *
 * A bucket then has a canonical tile that does not change as the map zooms, and a `span` that does:
 * `2^(display zoom - maxzoom)`. Its layout space is `LAYOUT_TILE_SIZE * span` wide, so the *same*
 * label sits at a different `tileAnchor` at each display zoom, and the index has to normalise that
 * away or the label is a new symbol at every level -- fading out and in on every zoom step.
 */
class CrossTileOverzoomTest {

    private companion object {
        const val MAXZOOM = 14
        const val TX = 8531
        const val TY = 5824
    }

    /** A label at [frac] across the ancestor tile, in a bucket shown at [bucketZoom]. */
    private fun bucketAt(bucketZoom: Int, vararg fracs: Pair<Double, Double>): SymbolBucket {
        val span = 1 shl (bucketZoom - MAXZOOM)
        val n = 2.0.pow(MAXZOOM)
        return SymbolFixtures.bucket(
            fracs.map { (fx, fy) ->
                SymbolFixtures.textInstance(
                    key = "Bahnhofstrasse",
                    global = Point((TX + fx) / n, (TY + fy) / n),
                    tileAnchor = Offset(
                        (fx * LAYOUT_TILE_SIZE * span).toFloat(),
                        (fy * LAYOUT_TILE_SIZE * span).toFloat(),
                    ),
                    width = 40f, height = 12f,
                )
            },
            z = MAXZOOM, x = TX, y = TY, span = span, bucketZoom = bucketZoom,
        )
    }

    @Test
    fun `a label keeps its identity as an overzoomed source is zoomed`() {
        val index = CrossTileSymbolIndex()

        val at15 = bucketAt(15, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(at15), density = 1f)
        val id = at15.instances.single().crossTileID

        val at16 = bucketAt(16, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(at16), density = 1f)

        assertEquals(id, at16.instances.single().crossTileID, "zooming in one overzoom step")

        val at17 = bucketAt(17, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(at17), density = 1f)
        assertEquals(id, at17.instances.single().crossTileID, "and another")
    }

    @Test
    fun `a label keeps its identity when an overzoomed source is zoomed back out`() {
        val index = CrossTileSymbolIndex()

        val at15 = bucketAt(15, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(at15), density = 1f)
        val id = at15.instances.single().crossTileID

        val at16 = bucketAt(16, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(at16), density = 1f)

        val backTo15 = bucketAt(15, 0.25 to 0.4)
        index.addLayer(SymbolFixtures.LAYER, listOf(backTo15), density = 1f)

        assertEquals(id, backTo15.instances.single().crossTileID, "zooming back out")
    }
}
