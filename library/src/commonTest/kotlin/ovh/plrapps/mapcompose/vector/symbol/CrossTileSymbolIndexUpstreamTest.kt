package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transcription of `maplibre-gl-js/src/symbol/cross_tile_symbol_index.test.ts`.
 *
 * Upstream's wording and assertions are kept so a failure can be traced back to a `describe`/`test`
 * block by search. Do not rewrite these to match the port -- if one fails, the port is wrong.
 *
 * Two adaptations, neither of them to the assertions:
 *
 * - **Anchors are in this port's units.** Upstream's are MVT `EXTENT` units with `EXTENT = 8192`;
 *   symbols here are laid out in style pixels against [LAYOUT_TILE_SIZE] (512), so every fixture
 *   coordinate is upstream's times `512 / 8192`. The rounding factor scales with it, so the grid
 *   cell a symbol lands in -- which is all `findMatches` compares -- is identical.
 * - **A tile is a [SymbolBucket].** Upstream's `makeTile` fakes a `Tile` around a bucket of symbol
 *   instances; here the bucket *is* the unit the index takes.
 *
 * Two upstream tests are absent, both because the code they exercise is deliberately not ported (see
 * [CrossTileSymbolIndex]): *reuses indexes when longitude is wrapped* needs `handleWrapJump`, and
 * *indexes data for findMatches perf* needs the `KDBush` path. Upstream's *claims the lowest-index
 * match among coincident symbols* is kept -- it asserts behaviour the linear path has too.
 */
class CrossTileSymbolIndexUpstreamTest {

    private companion object {
        const val LAYER = SymbolFixtures.LAYER

        /** Upstream's EXTENT-unit fixtures, in the units this port lays symbols out in. */
        fun anchor(extentUnits: Double): Float =
            (extentUnits * LAYOUT_TILE_SIZE / 8192.0).toFloat()
    }

    private fun makeSymbolInstance(x: Double, y: Double, key: String): SymbolInstance =
        SymbolFixtures.textInstance(
            key = key,
            tileAnchor = Offset(anchor(x), anchor(y)),
        )

    private fun makeTile(z: Int, x: Int, y: Int, instances: List<SymbolInstance>): SymbolBucket =
        SymbolFixtures.bucket(instances = instances, z = z, x = x, y = y)

    private fun CrossTileSymbolIndex.addLayer(buckets: List<SymbolBucket>) =
        addLayer(LAYER, buckets, density = 1f)

    // region CrossTileSymbolIndex.addLayer

    @Test
    fun `matches ids`() {
        val index = CrossTileSymbolIndex()

        val mainInstances = listOf(
            makeSymbolInstance(1000.0, 1000.0, "Detroit"),
            makeSymbolInstance(2000.0, 2000.0, "Toronto"),
        )
        val mainTile = makeTile(6, 8, 8, mainInstances)

        index.addLayer(listOf(mainTile))
        // Assigned new IDs
        assertEquals(1L, mainInstances[0].crossTileID)
        assertEquals(2L, mainInstances[1].crossTileID)

        val childInstances = listOf(
            makeSymbolInstance(2000.0, 2000.0, "Detroit"),
            makeSymbolInstance(2000.0, 2000.0, "Windsor"),
            makeSymbolInstance(3000.0, 3000.0, "Toronto"),
            makeSymbolInstance(4001.0, 4001.0, "Toronto"),
        )
        val childTile = makeTile(7, 16, 16, childInstances)

        index.addLayer(listOf(mainTile, childTile))
        // matched parent tile
        assertEquals(1L, childInstances[0].crossTileID)
        // does not match because of different key
        assertEquals(3L, childInstances[1].crossTileID)
        // does not match because of different location
        assertEquals(4L, childInstances[2].crossTileID)
        // matches with a slightly different location
        assertEquals(2L, childInstances[3].crossTileID)

        val parentInstances = listOf(makeSymbolInstance(500.0, 500.0, "Detroit"))
        val parentTile = makeTile(5, 4, 4, parentInstances)

        index.addLayer(listOf(mainTile, childTile, parentTile))
        // matched child tile
        assertEquals(1L, parentInstances[0].crossTileID)

        val grandchildInstances = listOf(
            makeSymbolInstance(4000.0, 4000.0, "Detroit"),
            makeSymbolInstance(4000.0, 4000.0, "Windsor"),
        )
        val grandchildTile = makeTile(8, 32, 32, grandchildInstances)

        index.addLayer(listOf(mainTile))
        index.addLayer(listOf(mainTile, grandchildTile))
        // Matches the symbol in `mainBucket`
        assertEquals(1L, grandchildInstances[0].crossTileID)
        // Does not match the previous value for Windsor because that tile was removed
        assertEquals(5L, grandchildInstances[1].crossTileID)
    }

    @Test
    fun `overwrites ids when re-adding`() {
        val index = CrossTileSymbolIndex()

        val mainInstances = listOf(makeSymbolInstance(1000.0, 1000.0, "Detroit"))
        val mainTile = makeTile(6, 8, 8, mainInstances)

        val childInstances = listOf(makeSymbolInstance(2000.0, 2000.0, "Detroit"))
        val childTile = makeTile(7, 16, 16, childInstances)

        // assigns a new id
        index.addLayer(listOf(mainTile))
        assertEquals(1L, mainInstances[0].crossTileID)

        // removes the tile
        index.addLayer(emptyList())

        // assigns a new id
        index.addLayer(listOf(childTile))
        assertEquals(2L, childInstances[0].crossTileID)

        // overwrites the old id to match the already-added tile
        index.addLayer(listOf(mainTile, childTile))
        assertEquals(2L, mainInstances[0].crossTileID)
        assertEquals(2L, childInstances[0].crossTileID)
    }

    @Test
    fun `does not duplicate ids within one zoom level`() {
        val index = CrossTileSymbolIndex()

        val mainInstances = listOf(
            makeSymbolInstance(1000.0, 1000.0, ""), // A
            makeSymbolInstance(1000.0, 1000.0, ""), // B
        )
        val mainTile = makeTile(6, 8, 8, mainInstances)

        val childInstances = listOf(
            makeSymbolInstance(2000.0, 2000.0, ""), // A'
            makeSymbolInstance(2000.0, 2000.0, ""), // B'
            makeSymbolInstance(2000.0, 2000.0, ""), // C'
        )
        val childTile = makeTile(7, 16, 16, childInstances)

        // assigns new ids
        index.addLayer(listOf(mainTile))
        assertEquals(1L, mainInstances[0].crossTileID)
        assertEquals(2L, mainInstances[1].crossTileID)

        assertContentEquals(listOf(1L, 2L), index.usedCrossTileIDs(LAYER, 6))

        // copies parent ids without duplicate ids in this tile
        index.addLayer(listOf(childTile))
        assertEquals(1L, childInstances[0].crossTileID) // A' copies from A
        assertEquals(2L, childInstances[1].crossTileID) // B' copies from B
        assertEquals(3L, childInstances[2].crossTileID) // C' gets new ID

        // Updates per-zoom usedCrossTileIDs
        assertContentEquals(emptyList(), index.usedCrossTileIDs(LAYER, 6))
        assertContentEquals(listOf(1L, 2L, 3L), index.usedCrossTileIDs(LAYER, 7))
    }

    @Test
    fun `does not regenerate ids for same zoom`() {
        val index = CrossTileSymbolIndex()

        val firstInstances = listOf(
            makeSymbolInstance(1000.0, 1000.0, ""), // A
            makeSymbolInstance(1000.0, 1000.0, ""), // B
        )
        val firstTile = makeTile(6, 8, 8, firstInstances)

        val secondInstances = listOf(
            makeSymbolInstance(1000.0, 1000.0, ""), // A'
            makeSymbolInstance(1000.0, 1000.0, ""), // B'
            makeSymbolInstance(1000.0, 1000.0, ""), // C'
        )
        val secondTile = makeTile(6, 8, 8, secondInstances)

        // assigns new ids
        index.addLayer(listOf(firstTile))
        assertEquals(1L, firstInstances[0].crossTileID)
        assertEquals(2L, firstInstances[1].crossTileID)

        assertContentEquals(listOf(1L, 2L), index.usedCrossTileIDs(LAYER, 6))

        // uses same ids when tile gets updated
        index.addLayer(listOf(secondTile))
        assertEquals(1L, secondInstances[0].crossTileID) // A' copies from A
        assertEquals(2L, secondInstances[1].crossTileID) // B' copies from B
        assertEquals(3L, secondInstances[2].crossTileID) // C' gets new ID

        assertContentEquals(listOf(1L, 2L, 3L), index.usedCrossTileIDs(LAYER, 6))
    }

    @Test
    fun `claims the lowest-index match among coincident symbols`() {
        val index = CrossTileSymbolIndex()

        val count = 130
        val mainInstances = mutableListOf<SymbolInstance>()
        val childInstances = mutableListOf<SymbolInstance>()
        for (i in 0 until count) {
            // all symbols are coincident and share the same key
            mainInstances += makeSymbolInstance(1000.0, 1000.0, "Dense")
            childInstances += makeSymbolInstance(2000.0, 2000.0, "Dense")
        }

        val mainTile = makeTile(6, 8, 8, mainInstances)
        val childTile = makeTile(7, 16, 16, childInstances)

        index.addLayer(listOf(mainTile))
        index.addLayer(listOf(mainTile, childTile))

        // Each child symbol should claim the lowest-index unclaimed parent
        // symbol, so parents are claimed in symbol-instance order.
        for (i in 0 until 12) {
            assertEquals(mainInstances[i].crossTileID, childInstances[i].crossTileID)
        }
    }

    // endregion

    @Test
    fun `CrossTileSymbolIndex pruneUnusedLayers`() {
        val index = CrossTileSymbolIndex()

        val instances = listOf(
            makeSymbolInstance(1000.0, 1000.0, ""), // A
            makeSymbolInstance(1000.0, 1000.0, ""), // B
        )
        val tile = makeTile(6, 8, 8, instances)

        // assigns new ids
        index.addLayer(listOf(tile))
        assertEquals(1L, instances[0].crossTileID)
        assertEquals(2L, instances[1].crossTileID)
        assertTrue(index.hasLayer(LAYER))

        // remove styleLayer
        index.pruneUnusedLayers(emptySet())
        assertFalse(index.hasLayer(LAYER))
    }
}

