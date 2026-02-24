package ovh.plrapps.mapcompose.vector

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.removeMarker
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.Symbol
import ovh.plrapps.mapcompose.vector.renderer.SymbolsProducer
import ovh.plrapps.mapcompose.vector.renderer.TileRenderer
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import pbandk.decodeFromByteArray
import kotlin.collections.component1
import kotlin.collections.component2

class VectorRasterizer(
    val configuration: MapLibreConfiguration,
    val densityState: MutableStateFlow<Density?>,
    val fontFamilyResolverState:  MutableStateFlow<FontFamily.Resolver?>,
    val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    val getTileStream: suspend (url: String, row: Int, col: Int, zoomLvl: Int) -> RawSource?
) {
    fun decodePBFFromByteArray(bytes: ByteArray): Tile? {
        return try {
            Tile.decodeFromByteArray(bytes)
        } catch (e: Exception) {
            println("Error decoding PBF: ${e.message}")
            null
        }
    }

    private fun renderTile(
        pbfList: Map<String, ByteArray>,
        zoom: Double,
        tileSize: Int,
        actualZoom: Double,
    ): ImageBitmap {
        val density = densityState.value ?: return emptyBitmap(tileSize)

        val imageBitmap = ImageBitmap(tileSize, tileSize)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        val allTiles = mutableMapOf<String, Tile?>()
        val tileRenderer = TileRenderer(configuration = configuration)

        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(tileSize.toFloat(), tileSize.toFloat())
        ) {
            for (styleLayer in configuration.style.layers) {
                val tile: Tile? = styleLayer.source.takeIf { !it.isNullOrBlank() }?.let { styleLayerSourceName ->
                    allTiles.getOrPut(styleLayerSourceName) {
                        styleLayerSourceName
                            .let { sourceName -> pbfList[sourceName] }
                            ?.let { bytes -> decodePBFFromByteArray(bytes) }
                    }
                }

                tileRenderer.render(
                    canvas = this,
                    tile = tile,
                    styleLayer = styleLayer,
                    zoom = zoom,
                    canvasSize = tileSize,
                    actualZoom = actualZoom,
                )
            }

        }
        allTiles.clear()
        return imageBitmap
    }

    private suspend fun fetchTile(url: String, row: Int, col: Int, zoomLvl: Int): Result<ByteArray> {
        try {
            // Check if the coroutine is cancelled before the network request
            currentCoroutineContext().ensureActive()

//            println("fetch the tile $url")
            val response = getTileStream(url, row, col, zoomLvl)

            // Check again after network request
            currentCoroutineContext().ensureActive()

            if (response == null) {
                return Result.failure(LoadTileException("fetch error"))
            }
            val result = response.buffered().use { bufferedSource ->
                bufferedSource.readByteArray()
            }
            return Result.success(result)
        } catch (e: CancellationException) {
            // We do not log cancellation as an error - this is normal behavior
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    // return sourceName: String and tile: ByteArray
    private suspend fun fetch(z: Int, x: Int, y: Int): Result<Map<String, ByteArray>> = supervisorScope {
        try {
            // Kick off concurrent fetches per source without failing the whole scope on one error
            val deferred = configuration.tileSources.map { (sourceName, ts) ->
                sourceName to async {
                    // Ensure still active before heavy work
                    coroutineContext.ensureActive()
                    fetchTile(ts.getTileUrl(z = z, x = x, y = y), row = y, col = x, zoomLvl = z).getOrThrow()
                }
            }

            // Await all; collect successes, ignore failures so partial data can still render
            val buffer = mutableMapOf<String, ByteArray>()
            for ((sourceName, d) in deferred) {
                runCatching { d.await() }
                    .onSuccess { bytes -> buffer[sourceName] = bytes }
                    .onFailure { /* ignore single source failure */ }
            }

            return@supervisorScope Result.success(buffer)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun emptyBitmap(size: Int): ImageBitmap {
        val density = densityState.value ?: return ImageBitmap(size, size)

        val imageBitmap = ImageBitmap(size, size)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(size.toFloat(), size.toFloat())
        ) {
            this.drawRect(
                color = Color.LightGray,
            )
        }
        return imageBitmap
    }

    suspend fun getTile(
        x: Int,
        y: Int,
        zoom: Double,
        tileSize: Int,
    ): ImageBitmap {
        val z = zoom.toInt()
        val pbfList = fetch(z = z, x = x, y = y).getOrElse { e ->
            println("ERROR: ${e.message}")
            return emptyBitmap(tileSize)
        }

        return renderTile(
            pbfList = pbfList,
            zoom = zoom,
            tileSize = tileSize,
            actualZoom = zoom,
        )
    }

    private val symbolsProducer = SymbolsProducer(
        textMeasurer = textMeasurerState,
        configuration = configuration
    )

    suspend fun produceSymbols(viewport: MVTViewport, tileSize: Int, z: Double): Result<List<Symbol>> {
        val density = densityState.value ?: return Result.failure(LoadTileException("density is null"))

        val symbols = mutableListOf<Symbol>()
        // Calculate overlay sizes based on tileMatrix
        val minY = viewport.tileMatrix.keys.min()
        val maxY = viewport.tileMatrix.keys.max()

//        println("viewport zoom = ${viewport.zoom} | zoom = $z")
        // For each tile in the viewport
        for (y in minY..maxY) {
            for (x in viewport.tileMatrix[y] ?: emptyList()) {
                // Loading PBF for the tile
                val pbfList = fetch(z = z.toInt(), x = x, y = y).getOrElse { e ->
                    println("ERROR: ${e.message}")
                    return Result.failure(e)
                }

                // draw symbols for this tile
                val allTiles = mutableMapOf<String, Tile?>()
                for (styleLayer in configuration.style.layers) {
                    if (styleLayer !is SymbolLayer) continue

                    val tile: Tile? =
                        styleLayer.source.takeIf { !it.isNullOrBlank() }?.let { styleLayerSourceName ->
                            allTiles.getOrPut(styleLayerSourceName) {
                                styleLayerSourceName
                                    .let { sourceName -> pbfList[sourceName] }
                                    ?.let { bytes -> decodePBFFromByteArray(bytes) }
                            }
                        }
                    if (tile == null) continue

                    val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer }
                    if (tileLayer == null) continue


                    symbolsProducer.produce(
                        tile = tile,
                        styleLayer = styleLayer,
                        zoom = z,
                        canvasSize = tileSize,
                        actualZoom = z,
                        tileX = x,
                        tileY = y,
                        density = density,
                    ).let {
                        symbols.addAll(it)
                    }
                }
            }
        }

        return Result.success(symbols)
    }

    val symbols = MutableStateFlow<List<Symbol>>(emptyList())
    private var prevSymbols = emptyList<Symbol>()

    fun updateSymbols(nextSymbols: List<Symbol>, state: MapState) {
//        println("updateSymbols ${nextSymbols.size}")
        checkIndexErrors(nextSymbols)

        val nextSymbolsId = nextSymbols.map { it.id }
        val prevSymbolsId = prevSymbols.map { it.id }

        // We remove markers that no longer exist
        prevSymbolsId.forEach { prevSymbolId ->
            val shouldRemove = prevSymbolId !in nextSymbolsId
            if (shouldRemove) {
                state.removeMarker(prevSymbolId)
            }
        }

        // Adding new markers
        for (symbol in nextSymbols) {
            if (symbol.id in prevSymbolsId) continue

            state.addMarker(
                id = symbol.id,
                x = symbol.global.x,
                y = symbol.global.y,
                relativeOffset = symbol.align,
                clickable = false
            ) {
                symbol.render()
            }
        }
        prevSymbols = nextSymbols
        symbols.value = nextSymbols
    }

    private class Counter {
        var value: Int = 0
    }

    private fun checkIndexErrors(nextSymbols: List<Symbol>) {
        val counters = mutableMapOf<String, Counter>()

        nextSymbols.forEach { symbol ->
            counters.getOrPut(symbol.id) { Counter() }.value++
        }
        counters.forEach { (id, value) ->
            if (value.value > 1) {
                println("[ERROR]: Id $id not unique (${value.value})")
            }
        }
    }
}

class LoadTileException(msg: String) : Exception(msg)