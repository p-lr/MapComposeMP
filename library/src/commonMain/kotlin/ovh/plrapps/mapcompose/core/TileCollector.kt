package ovh.plrapps.mapcompose.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.io.Source
import kotlinx.io.buffered
import ovh.plrapps.mapcompose.utils.IODispatcher
import kotlin.concurrent.Volatile
import kotlin.math.pow


/**
 * The engine of MapCompose. The view-model uses two channels to communicate with the [TileCollector]:
 * * one to send [TileSpec]s (a [SendChannel])
 * * one to receive [TileSpec]s (a [ReceiveChannel])
 *
 * The [TileCollector] encapsulates all the complexity that transforms a [TileSpec] into a [Tile].
 * ```
 *                                              _____________________________________________________________________
 *                                             |                           TileCollector             ____________    |
 *                                  tiles      |                                                    |  ________  |   |
 *              ---------------- [*********] <----------------------------------------------------- | | worker | |   |
 *             |                               |                                                    |  --------  |   |
 *             ↓                               |                                                    |  ________  |   |
 *  _____________________                      |                                   tileSpecs        | | worker | |   |
 * | TileCanvasViewModel |                     |    _____________________  <---- [**********] <---- |  --------  |   |
 *  ---------------------  ----> [*********] ----> | tileCollectorKernel |                          |  ________  |   |
 *                                tileSpecs    |    ---------------------  ----> [**********] ----> | | worker | |   |
 *                                             |                                   tileSpecs        |  --------  |   |
 *                                             |                                                    |____________|   |
 *                                             |                                                      worker pool    |
 *                                             |                                                                     |
 *                                              ---------------------------------------------------------------------
 * ```
 * This architecture is an example of Communicating Sequential Processes (CSP).
 *
 * @since 22/06/19
 */
internal class TileCollector(
    private val workerCount: Int,
    private val optimizeForLowEndDevices: Boolean,
    private val tileSize: Int,
    /**
     * Whether a spec is still worth producing. A [BitmapTileProvider] renders for a long time, and a
     * pinch across a level makes every tile it was asked for obsolete before it is done; such a tile
     * is dropped without an output [Tile], so that it is simply requested again if it reappears.
     */
    private val isWanted: (TileSpec) -> Boolean = { true },
) {
    @Volatile
    var isIdle: Boolean = true

    /**
     * Sets up the tile collector machinery. The architecture is inspired from
     * [Kotlin Conf 2018](https://www.youtube.com/watch?v=a3agLJQ6vt8).
     * It support back-pressure, and avoids deadlock in CSP taking into account recommendations of
     * this [article](https://medium.com/@elizarov/deadlocks-in-non-hierarchical-csp-e5910d137cc),
     * which is from the same author.
     *
     * @param [tileSpecs] channel of [TileSpec], which capacity should be [Channel.RENDEZVOUS].
     * @param [tilesOutput] channel of [Tile], which should be set as [Channel.RENDEZVOUS].
     */
    suspend fun collectTiles(
        tileSpecs: ReceiveChannel<TileSpec>,
        tilesOutput: SendChannel<Tile>,
        layers: List<Layer>
    ) = coroutineScope {
        val tilesToDownload = Channel<TileSpec>(capacity = Channel.RENDEZVOUS)
        val tilesDownloadedFromWorker = Channel<TileSpec>(capacity = 1)

        repeat(workerCount) {
            worker(
                tilesToDownload,
                tilesDownloadedFromWorker,
                tilesOutput,
                layers
            )
        }
        tileCollectorKernel(tileSpecs, tilesToDownload, tilesDownloadedFromWorker)
    }

    private fun CoroutineScope.worker(
        tilesToDownload: ReceiveChannel<TileSpec>,
        tilesDownloaded: SendChannel<TileSpec>,
        tilesOutput: SendChannel<Tile>,
        layers: List<Layer>,
    ) = launch(workerDispatcher) {

        val layerIds = layers.map { it.id }
        val paint = Paint()
        val workerData = makeWorkerData()

        for (spec in tilesToDownload) {
            if (layers.isEmpty() || !isWanted(spec)) {
                tilesDownloaded.send(spec)
                continue
            }

            val subSamplingRatio = 2.0.pow(spec.subSample).toInt()
            val bitmapForLayers = fetchLayers(spec, layers) ?: run {
                /* Abandoned because the tile stopped being visible: no output, so that the actor
                 * which submits specs does not record it as processed. */
                tilesDownloaded.send(spec)
                null
            } ?: continue

            try {
                val firstLayerImage = bitmapForLayers.first().let { first ->
                    first.bitmap?.let { adoptRenderedBitmap(it, hasLayers = layers.size > 1) }
                        ?: first.source?.decodeFirstLayer(
                            hasLayers = layers.size > 1,
                            optimizeForLowEndDevices = optimizeForLowEndDevices,
                            subSamplingRatio = subSamplingRatio,
                            workerData = workerData
                        )
                } ?: run {
                    tilesDownloaded.send(spec)
                    /* When the decoding failed or if there's nothing to decode, then send back the
                     * Tile just as in normal processing, so that the actor which submits tiles specs
                     * to the collector knows that this tile has been processed and does not
                     * immediately re-sends the same spec. */
                    tilesOutput.send(
                        Tile(
                            spec.zoom,
                            spec.row,
                            spec.col,
                            spec.subSample,
                            layerIds,
                            layers.map { it.alpha }
                        )
                    )
                    bitmapForLayers.drop(1).forEach { it.bitmap?.let(::releaseRenderedBitmap) }
                    null
                } ?: continue // If the decoding of the first layer failed, skip the rest

                val resultImage = if (layers.size > 1) {
                    val canvas = Canvas(firstLayerImage)

                    var previousLayer: ImageBitmap? = null
                    for (result in bitmapForLayers.drop(1)) {
                        paint.alpha = result.layer.alpha
                        if (result.bitmap != null) {
                            /* A rendered layer may be of another resolution than the first one; it
                             * is drawn over the same area. */
                            canvas.drawImageRect(
                                image = result.bitmap,
                                dstSize = IntSize(firstLayerImage.width, firstLayerImage.height),
                                paint = paint,
                            )
                            releaseRenderedBitmap(result.bitmap)
                            continue
                        }
                        if (result.source == null) continue
                        previousLayer = result.source.decodeOverlay(
                            previousLayer = previousLayer,
                            tileSize = tileSize,
                            optimizeForLowEndDevices = optimizeForLowEndDevices,
                            subSamplingRatio = subSamplingRatio,
                            workerData = workerData
                        ) ?: continue
                        canvas.drawImage(
                            image = previousLayer,
                            topLeftOffset = Offset.Zero,
                            paint = paint
                        )
                    }

                    processFinalImage(firstLayerImage, previousLayer)
                } else firstLayerImage

                val tile = Tile(
                    spec.zoom,
                    spec.row,
                    spec.col,
                    spec.subSample,
                    layerIds,
                    layers.map { it.alpha }
                ).apply {
                    this.bitmap = resultImage
                }
                tilesOutput.send(tile)
                tilesDownloaded.send(spec)
            } finally {
                /* The collector owns the sources it obtained from the TileStreamProvider, so it
                 * closes them on every exit path - including the `continue` above, and cancellation
                 * at either `send`. A source left open holds a file descriptor until the GC
                 * finalizes it. */
                for (layerResult in bitmapForLayers) {
                    runCatching { layerResult.source?.close() }
                }
            }
        }
    }

    /**
     * Every layer's contribution to [spec], fetched concurrently, or null when a [BitmapTileProvider]
     * gave up on it because it stopped being wanted.
     *
     * A provider that skipped a tile which is wanted again by the time every layer answered -- the map
     * went away and came straight back -- is asked once more rather than leaving a hole.
     */
    private suspend fun fetchLayers(spec: TileSpec, layers: List<Layer>): List<LayerResult>? = coroutineScope {
        val wanted = { isWanted(spec) }
        repeat(2) {
            val results = layers.map { layer ->
                async {
                    when (val provider = layer.tileStreamProvider) {
                        is BitmapTileProvider ->
                            when (val result = provider.getTileBitmap(spec.row, spec.col, spec.zoom, spec.subSample, wanted)) {
                                is BitmapTileResult.Rendered -> LayerResult(layer, bitmap = result.bitmap)
                                BitmapTileResult.Empty -> LayerResult(layer)
                                BitmapTileResult.Skipped -> null
                            }

                        else -> LayerResult(
                            layer,
                            source = provider.getTileStream(spec.row, spec.col, spec.zoom)?.buffered(),
                        )
                    }
                }
            }.awaitAll()

            if (results.all { it != null }) return@coroutineScope results.filterNotNull()
            results.forEach { result ->
                result?.bitmap?.let(::releaseRenderedBitmap)
                /* This attempt is discarded, and the sources it opened are the collector's to close. */
                runCatching { result?.source?.close() }
            }
            if (!isWanted(spec)) return@coroutineScope null
        }
        null
    }

    private fun CoroutineScope.tileCollectorKernel(
        tileSpecs: ReceiveChannel<TileSpec>,
        tilesToDownload: SendChannel<TileSpec>,
        tilesDownloadedFromWorker: ReceiveChannel<TileSpec>,
    ) = launch(Dispatchers.Default) {

        val specsBeingProcessed = mutableListOf<TileSpec>()

        while (true) {
            select<Unit> {
                tilesDownloadedFromWorker.onReceive {
                    specsBeingProcessed.remove(it)
                    isIdle = specsBeingProcessed.isEmpty()
                }
                tileSpecs.onReceive {
                    if (it !in specsBeingProcessed) {
                        /* Add it to the list of specs being processed */
                        specsBeingProcessed.add(it)
                        isIdle = false

                        /* Now download the tile */
                        tilesToDownload.send(it)
                    }
                }
            }
        }
    }

    private val workerDispatcher = IODispatcher.limitedParallelism(workerCount, "tileCollector")
}

internal expect fun makeWorkerData(): WorkerData
internal expect class WorkerData

internal expect fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int,
    workerData: WorkerData
): ImageBitmap?

internal expect fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int,
    workerData: WorkerData
): ImageBitmap?

internal expect fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap

/** One layer's answer for a tile: bytes to decode, or a bitmap a [BitmapTileProvider] rendered. */
private class LayerResult(val layer: Layer, val source: Source? = null, val bitmap: ImageBitmap? = null)

/**
 * Takes ownership of a bitmap a [BitmapTileProvider] rendered, as the first layer of a tile.
 *
 * On Android a single-layer tile is moved to a hardware bitmap, as a decoded one would have been
 * decoded straight into one, and the software original is recycled. Elsewhere it is used as is.
 */
internal expect fun adoptRenderedBitmap(bitmap: ImageBitmap, hasLayers: Boolean): ImageBitmap

/** Frees a rendered bitmap that was drawn onto another layer, or never used. */
internal expect fun releaseRenderedBitmap(bitmap: ImageBitmap)
