package ovh.plrapps.mapcompose.core

import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Test the [TileCollector.collectTiles] engine. The following assertions are tested:
 * - If [TileSpec]s are send to the input channel, corresponding [Tile]s are received from the
 * output channel (from the [TileCollector.collectTiles] point of view).
 * - The bitmap of the [Tile]s produced should be consistent with the output of the flow.
 */
class TileCollectorTest {

    private val tileSize = 256

    private val tileOnePixel = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48,
        0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x01, 0x03, 0x00, 0x00, 0x00,
        0x25, 0xDB.toByte(), 0x56, 0xCA.toByte(), 0x00, 0x00, 0x00, 0x03, 0x50, 0x4C, 0x54, 0x45,
        0x00, 0x00, 0x00, 0xA7.toByte(), 0x7A, 0x3D, 0xDA.toByte(), 0x00, 0x00, 0x00, 0x01, 0x74,
        0x52, 0x4E, 0x53, 0x00, 0x40, 0xE6.toByte(), 0xD8.toByte(), 0x66, 0x00, 0x00, 0x00, 0x0A,
        0x49, 0x44, 0x41, 0x54, 0x08, 0xD7.toByte(), 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
        0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fullTest() = runTest {
        /* Setup the channels */
        val visibleTileLocationsChannel = Channel<TileSpec>(capacity = Channel.RENDEZVOUS)
        val tilesOutput = Channel<Tile>(capacity = Channel.RENDEZVOUS)

        val tileStreamProvider = TileStreamProvider { _, _, _ ->
            makeTile()
        }

        val layers = listOf(
            Layer("default", tileStreamProvider)
        )

        /* Start collecting tiles */
        val tileCollector = TileCollector(1, false, tileSize)
        val tileCollectorJob = launch {
            tileCollector.collectTiles(visibleTileLocationsChannel, tilesOutput, layers)
        }

        fun CoroutineScope.consumeTiles(tileChannel: ReceiveChannel<Tile>) = launch {
            var receivedTiles = 0
            for (tile in tileChannel) {
                println("received tile ${tile.zoom}-${tile.row}-${tile.col}")
                // BitmapFactory is a stub on Android unit tests, so bitmap may be null there.
                // On all other platforms (desktop, iOS, WASM) the assertions run normally.
                tile.bitmap?.let { bitmap ->
                    assertEquals(1, bitmap.width)
                    assertEquals(1, bitmap.height)
                    // Embedded PNG is a single fully-transparent pixel (palette black + tRNS alpha 0).
                    assertEquals(0f, bitmap.toPixelMap()[0, 0].alpha)
                }
                receivedTiles += 1

                if (tile.zoom == 6 && tile.row == 6 && tile.col == 6) {
                    println("received poison pill")
                    assertEquals(7, receivedTiles)
                    cancel()
                    tileCollectorJob.cancel()
                }
            }
        }

        /* Start consuming tiles */
        consumeTiles(tilesOutput)

        launch {
            val locations1 = listOf(
                TileSpec(0, 0, 0),
                TileSpec(0, 1, 1),
                TileSpec(0, 2, 1)
            )
            for (spec in locations1) {
                visibleTileLocationsChannel.send(spec)
            }

            val locations2 = listOf(
                TileSpec(1, 0, 0),
                TileSpec(1, 1, 1),
                TileSpec(1, 2, 1),
                TileSpec(6, 6, 6),  // poison pill
            )

            for (spec in locations2) {
                visibleTileLocationsChannel.send(spec)
            }
        }
        Unit
    }

    private fun makeTile(): Buffer {
        return Buffer().apply { write(tileOnePixel) }
    }
}