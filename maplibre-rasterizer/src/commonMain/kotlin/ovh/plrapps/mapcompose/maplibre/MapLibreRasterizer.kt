package ovh.plrapps.mapcompose.maplibre

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.TileCache
import ovh.plrapps.mapcompose.maplibre.data.httpClient
import ovh.plrapps.mapcompose.maplibre.renderer.Symbol
import ovh.plrapps.mapcompose.maplibre.renderer.SymbolsProducer
import ovh.plrapps.mapcompose.maplibre.renderer.TileRenderer
import ovh.plrapps.mapcompose.maplibre.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer
import pbandk.decodeFromByteArray

class MapLibreRasterizer(
    val configuration: MapLibreConfiguration,
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val textMeasurer: TextMeasurer,
    val tileCache: TileCache?
) {
    private val tileRenderer = TileRenderer(
        configuration = configuration
    )

    fun decodePBFFromByteArray(bytes: ByteArray): Tile {
        return Tile.decodeFromByteArray(bytes)
    }

    private fun renderTile(
        pbfList: Map<String, ByteArray>,
        zoom: Double,
        tileSize: Int,
        actualZoom: Double,
    ): ImageBitmap {
        val imageBitmap = ImageBitmap(tileSize, tileSize)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        val allTiles = mutableMapOf<String, Tile?>()

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

    private suspend fun fetchTile(url: Url): Result<ByteArray> {
        try {
            // Check if the coroutine is cancelled before the network request
            coroutineContext.ensureActive()
            
            println("fetch the tile $url")
            val response = httpClient.get(url)
            println("fetch result for $url | ${response.status}")
            
            // Check again after network request
            coroutineContext.ensureActive()
            
            if (response.status != HttpStatusCode.OK) {
                return Result.failure(LoadTileException("fetch error"))
            }
            return Result.success(response.bodyAsBytes())
        } catch (e: CancellationException) {
            // We do not log cancellation as an error - this is normal behavior
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    // return sourceName: String and tile: ByteArray
    private suspend fun fetch(z: Int, x: Int, y: Int): Result<Map<String, ByteArray>> {
        val buffer = mutableMapOf<String, ByteArray>()
        
        val results = configuration.tileSources.map { (sourceName, ts) ->
            sourceName to kotlin.runCatching {
                // Checking if the coroutine has been cancelled
                coroutineContext.ensureActive()
                
                val key = "x${x}_y${y}_z${z}.pbf"
                val pbf = tileCache?.get(sourceName, key) 
                    ?: fetchTile(ts.getTileUrl(z = z, x = x, y = y)).getOrThrow()
                tileCache?.put(sourceName, key, pbf)
                pbf
            }
        }
        
        // check result
        for ((sourceName, result) in results) {
            result.fold(
                onSuccess = { pbf -> buffer[sourceName] = pbf },
                onFailure = { e -> 
                    return Result.failure(LoadTileException("Failed to fetch tile for source '$sourceName': ${e.message}"))
                }
            )
        }
        
        return if (buffer.size == configuration.tileSources.size) {
            Result.success(buffer)
        } else {
            Result.failure(LoadTileException("Not all tiles were loaded: expected ${configuration.tileSources.size}, got ${buffer.size}"))
        }
    }

    private fun emptyBitmap(size: Int): ImageBitmap {
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
        textMeasurer = textMeasurer,
        configuration = configuration
    )

    suspend fun produceSymbols(viewport: MVTViewport, tileSize: Int, z: Double): Result<List<Symbol>> {
        val symbols = mutableListOf<Symbol>()
        // Calculate overlay sizes based on tileMatrix
        val minY = viewport.tileMatrix.keys.min()
        val maxY = viewport.tileMatrix.keys.max()

        println("viewport zoom = ${viewport.zoom} | zoom = $z")
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
                                    ?.let { bytes -> Tile.decodeFromByteArray(bytes) }
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
}

class LoadTileException(msg: String) : Exception(msg)