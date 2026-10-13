package ovh.plrapps.mapcompose.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The [BitmapTileProvider] path through [TileCollector]: a rendered bitmap is handed over as is, and
 * a tile that stopped being wanted produces no output at all -- not an empty [Tile], which the canvas
 * would keep as a blank until the next eviction.
 *
 * In `skiaTest` because it allocates an `ImageBitmap`, which androidHostTest cannot.
 */
class TileCollectorBitmapTest {

    private class Provider(
        private val answer: (call: Int, spec: TileSpec) -> BitmapTileResult,
    ) : BitmapTileProvider {
        var calls = 0

        override suspend fun getTileBitmap(
            row: Int,
            col: Int,
            zoomLvl: Int,
            subSample: Int,
            isWanted: () -> Boolean,
        ): BitmapTileResult = answer(calls++, TileSpec(zoomLvl, row, col, subSample))
    }

    /** Runs [specs] through a one-worker collector and returns the tiles it output, in order. */
    private suspend fun kotlinx.coroutines.CoroutineScope.collect(
        provider: Provider,
        specs: List<TileSpec>,
        expectedOutputs: Int,
        isWanted: (TileSpec) -> Boolean = { true },
    ): List<Tile> {
        val input = Channel<TileSpec>(Channel.RENDEZVOUS)
        val output = Channel<Tile>(Channel.RENDEZVOUS)
        val collector = TileCollector(1, false, 256, isWanted)
        val job = launch { collector.collectTiles(input, output, listOf(Layer("vector", provider))) }
        launch { for (spec in specs) input.send(spec) }
        val tiles = List(expectedOutputs) { output.receive() }
        job.cancel()
        return tiles
    }

    @Test
    fun `a rendered bitmap is handed over as is`() = runTest {
        val bitmap = ImageBitmap(8, 8)
        val provider = Provider { _, _ -> BitmapTileResult.Rendered(bitmap) }

        val tiles = collect(provider, listOf(TileSpec(3, 1, 2)), expectedOutputs = 1)

        assertSame(bitmap, tiles.single().bitmap)
        assertEquals(TileSpec(3, 1, 2), TileSpec(tiles.single().zoom, tiles.single().row, tiles.single().col))
    }

    @Test
    fun `an empty answer still outputs a tile so the spec counts as processed`() = runTest {
        val provider = Provider { _, _ -> BitmapTileResult.Empty }

        val tiles = collect(provider, listOf(TileSpec(3, 1, 2)), expectedOutputs = 1)

        assertEquals(null, tiles.single().bitmap)
    }

    @Test
    fun `a tile skipped because it is no longer wanted outputs nothing`() = runTest {
        val stale = TileSpec(3, 0, 0)
        val fresh = TileSpec(4, 0, 0)
        var staleWanted = true
        val provider = Provider { _, spec ->
            if (spec == stale) {
                // The map moved on while this tile was rendering.
                staleWanted = false
                BitmapTileResult.Skipped
            } else {
                BitmapTileResult.Rendered(ImageBitmap(4, 4))
            }
        }

        val tiles = collect(
            provider, listOf(stale, fresh), expectedOutputs = 1,
            isWanted = { it != stale || staleWanted },
        )

        assertEquals(4, tiles.single().zoom, "only the wanted tile came out")
    }

    @Test
    fun `a spec that is unwanted when a worker picks it up is never rendered`() = runTest {
        val stale = TileSpec(3, 0, 0)
        val fresh = TileSpec(4, 0, 0)
        val rendered = mutableListOf<TileSpec>()
        val provider = Provider { _, spec ->
            rendered += spec
            BitmapTileResult.Rendered(ImageBitmap(4, 4))
        }

        collect(provider, listOf(stale, fresh), expectedOutputs = 1, isWanted = { it != stale })

        assertEquals(listOf(fresh), rendered)
    }

    @Test
    fun `a skipped tile that is wanted again is asked for once more`() = runTest {
        /* The map went away and came straight back while the tile rendered: leaving it out would
         * leave a hole until the map moved again. */
        val provider = Provider { call, _ ->
            if (call == 0) BitmapTileResult.Skipped else BitmapTileResult.Rendered(ImageBitmap(4, 4))
        }

        val tiles = collect(provider, listOf(TileSpec(3, 0, 0)), expectedOutputs = 1)

        assertEquals(2, provider.calls)
        assertEquals(4, tiles.single().bitmap?.width)
    }
}
