package ovh.plrapps.mapcompose.vector.symbol

import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which tiles the layout pass covers, which is what its whole cost is proportional to. */
class LayoutTileSetTest {

    private fun viewport(
        tileMatrix: Map<Int, IntRange>,
        overflow: List<Map<Int, IntRange>> = emptyList(),
    ) = MVTViewport(
        width = 512f,
        height = 512f,
        bearing = 0f,
        pitch = 0f,
        zoom = 6f,
        tileMatrix = tileMatrix,
        overflowTileMatrices = overflow,
    )

    @Test
    fun `a wrap-around window does not claim the tiles between it and the main one`() = runTest {
        /* An infinite-scroll viewport straddling the antimeridian shows a window at each edge of the
         * world. A `TileMatrix` is one contiguous column range per row, so these used to be merged
         * with `min(first)..max(last)` -- which for `0..1` and `62..63` is `0..63`, the entire tile
         * row, for every symbol layer in the style. That alone puts the layout pass permanently in
         * the rebuild-everything state the bucket cache exists to keep it out of. */
        val rasterizer = SymbolFixtures.rasterizer()

        val buckets = rasterizer.layoutBuckets(
            viewport(tileMatrix = mapOf(4 to 0..1), overflow = listOf(mapOf(4 to 62..63))),
            z = 6.0,
        ).getOrThrow().buckets

        // Each window is grown by one in each direction: columns 0..2 and 61..63, rows 3..5.
        assertEquals(3 * 6, buckets.size)
    }

    @Test
    fun `one window is the visible tiles plus a one-tile ring`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()

        val buckets = rasterizer.layoutBuckets(viewport(mapOf(4 to 4..4)), z = 6.0).getOrThrow().buckets

        assertEquals(9, buckets.size)
    }
}
