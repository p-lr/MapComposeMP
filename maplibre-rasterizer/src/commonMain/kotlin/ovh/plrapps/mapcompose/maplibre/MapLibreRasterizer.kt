package ovh.plrapps.mapcompose.maplibre

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.httpClient
import ovh.plrapps.mapcompose.maplibre.renderer.TileRenderer
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import ovh.plrapps.mapcompose.maplibre.data.TileCache
import ovh.plrapps.mapcompose.maplibre.renderer.SymbolsRenderer
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer
import pbandk.decodeFromByteArray

class MapboxRasterizer(
    val configuration: MapLibreConfiguration,
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val textMeasurer: TextMeasurer,
    val tileCache: TileCache?
) {
    private val maxZoom: Int = configuration.tileSources.minOf { it.value.tileJson.maxzoom }

    private val tileRenderer = TileRenderer(
        configuration = configuration
    )

    private val symbolsRenderer = SymbolsRenderer(
        textMeasurer = textMeasurer,
        configuration = configuration
    )

    private var lastViewport: MVTViewport? = null
    private var lastTileSize: Int? = null
    private var lastOverlay: ImageBitmap? = null
    private var lastZoom: Double? = null

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
        println("fetch the tile $url")
        val response = httpClient.get(url)
        println("fetch result for $url | ${response.status}")
        if (response.status != HttpStatusCode.OK) {
            return Result.failure(LoadTileException("fetch error"))
        }
        return Result.success(response.bodyAsBytes())
    }

    // return sourceName: String and tile: ByteArray
    private suspend fun fetch(z: Int, x: Int, y: Int): Result<Map<String, ByteArray>> {
        val buffer = mutableMapOf<String, ByteArray>()
        var isLoaded = mutableListOf<Boolean>()
        configuration.tileSources.forEach { (sourceName, ts) ->
            val key = "${sourceName}_x${x}_y${y}_z${z}.pbf"
            try {
                val pbf = tileCache?.get(key) ?: fetchTile(ts.getTileUrl(z = z, x = x, y = y)).getOrThrow()
                tileCache?.put(key, pbf)
                buffer[sourceName] = pbf
                isLoaded.add(true)
            } catch (e: Exception) {
                return Result.failure(LoadTileException("fetch error: ${e.message}"))
            }
        }
        return if (isLoaded.isNotEmpty() && isLoaded.all { it }) {
            Result.success(buffer)
        } else {
            Result.failure(LoadTileException("buffer is empty"))
        }
    }

    suspend fun getTile(
        x: Int,
        y: Int,
        zoom: Double,
        tileSize: Int,
        viewport: MVTViewport,
    ): ImageBitmap {
        val z = zoom.toInt()
        val pbfList = fetch(z = z, x = x, y = y).getOrElse { e ->
            println("ERROR: ${e.message}")
            return emptyBitmap(tileSize)
        }
        val minX = viewport.tileMatrix.values.minOf { it.min() }
        val minY = viewport.tileMatrix.keys.min()
        val symbolsOverlay = renderOrGetSymbolsOverlay(viewport, tileSize, z = z.toDouble())
        val tile = renderTile(
            pbfList = pbfList,
            zoom = zoom,
            tileSize = tileSize,
            actualZoom = zoom,
        )
        return applySymbolsOnTile(
            tile = tile,
            tileX = x,
            tileY = y,
            symbolsOverlay = symbolsOverlay,
            minX = minX,
            minY = minY,
            tileSize = tileSize
        )
    }

    /**
     * Applies the corresponding part to the tile
     */
    private fun applySymbolsOnTile(
        tile: ImageBitmap,
        tileX: Int,
        tileY: Int,
        symbolsOverlay: ImageBitmap,
        minX: Int,
        minY: Int,
        tileSize: Int
    ): ImageBitmap {
        val srcX = (tileX - minX) * tileSize
        val srcY = (tileY - minY) * tileSize

        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(tile),
            size = Size(tileSize.toFloat(), tileSize.toFloat())
        ) {
            drawImage(
                image = symbolsOverlay,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(tileSize, tileSize),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(tileSize, tileSize),
            )
        }

        return tile
    }

    /**
     * Creates or returns a previously created Symbols overlay for the current viewport.
     */
    private suspend fun renderOrGetSymbolsOverlay(viewport: MVTViewport, tileSize: Int, z: Double): ImageBitmap {
        // Checking if a cached overlay can be used
        if (lastViewport == viewport && lastTileSize == tileSize && lastOverlay != null && lastZoom != z) {
            return lastOverlay!!
        }
        // Calculate overlay sizes based on tileMatrix
        val minX = viewport.tileMatrix.values.minOf { it.min() }
        val maxX = viewport.tileMatrix.values.maxOf { it.max() }
        val minY = viewport.tileMatrix.keys.min()
        val maxY = viewport.tileMatrix.keys.max()

        val width = (maxX - minX + 1) * tileSize
        val height = (maxY - minY + 1) * tileSize

        val imageBitmap = ImageBitmap(width, height)
        val canvas = Canvas(imageBitmap)
        val collisionDetector = CollisionDetector()

        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat())
        ) {
            if (configuration.enableDebugTileGrid) {
                drawGrid(viewport = viewport, tileSize = tileSize, canvas = this)
            }
            // For each tile in the viewport
            for (y in minY..maxY) {
                for (x in viewport.tileMatrix[y] ?: emptyList()) {
                    // Calculate the position of the tile in the overlay
                    val tileX = x - minX
                    val tileY = y - minY
                    // Loading PBF for the tile
                    val pbfList = fetch(z = z.toInt(), x = x, y = y).getOrElse { e ->
                        println("ERROR: ${e.message}")
                        return imageBitmap
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
                        val offset = Offset(
                            x = tileX * tileSize.toFloat(),
                            y = tileY * tileSize.toFloat()
                        )


                        symbolsRenderer.render(
                            drawScope = this,
                            tile = tile,
                            styleLayer = styleLayer,
                            zoom = z.toDouble(),
                            canvasSize = tileSize,
                            actualZoom = z.toDouble(),
                            collisionDetector = collisionDetector,
                            offsetInViewport = offset,
                        )

                    }
                }
            }
        }

        lastViewport = viewport
        lastTileSize = tileSize
        lastOverlay = imageBitmap
        lastZoom = z
        return imageBitmap
    }


    private fun drawGrid(viewport: MVTViewport, tileSize: Int, canvas: DrawScope) {
        val minX = viewport.tileMatrix.values.minOf { it.min() }
        val maxX = viewport.tileMatrix.values.maxOf { it.max() }
        val minY = viewport.tileMatrix.keys.min()
        val maxY = viewport.tileMatrix.keys.max()

        val width = (maxX - minX + 1) * tileSize
        val height = (maxY - minY + 1) * tileSize

        val lineColor = Color(0x73ff0000)
        canvas.apply {
            for (x in 0..maxX - minX) {
                drawLine(
                    color = lineColor,
                    start = Offset(x * tileSize.toFloat(), 0f),
                    end = Offset(x * tileSize.toFloat(), height.toFloat()),
                    strokeWidth = 2f
                )
            }

            for (y in 0..maxY - minY) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y * tileSize.toFloat()),
                    end = Offset(width.toFloat(), y * tileSize.toFloat()),
                    strokeWidth = 2f
                )
            }

            // tiles id
            for (y in minY..maxY) {
                for (x in viewport.tileMatrix[y] ?: emptyList()) {
                    val tileX = x - minX
                    val tileY = y - minY
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "$x/$y",
                        topLeft = Offset(
                            tileX * tileSize.toFloat() + 5f,
                            tileY * tileSize.toFloat() + 15f
                        ),
                        style = TextStyle(
                            color = Color.Red,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
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
}

class LoadTileException(msg: String) : Exception(msg)