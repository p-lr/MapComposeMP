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
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.httpClient
import ovh.plrapps.mapcompose.maplibre.renderer.MapLayerRenderer
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import ovh.plrapps.mapcompose.maplibre.data.TileCache
import pbandk.decodeFromByteArray

class MapboxRasterizer(
    val configuration: MapLibreConfiguration,
    val density: Density,
    val fontFamilyResolver: FontFamily.Resolver,
    val textMeasurer: TextMeasurer,
    val tileCache: TileCache?
) {
    private val renderer = MapLayerRenderer(
        textMeasurer = textMeasurer
    )

    fun decodePBFFromByteArray(bytes: ByteArray): Tile {
        return Tile.decodeFromByteArray(bytes)
    }

    fun render(pbfList: Map<String, ByteArray>, zoom: Double, tileSize: Int): ImageBitmap {
        val imageBitmap = ImageBitmap(tileSize, tileSize)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        val collisionDetector = CollisionDetector()
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

                renderer.render(
                    canvas = this,
                    tile = tile,
                    styleLayer = styleLayer,
                    collisionDetector = collisionDetector,
                    zoom = zoom,
                    canvasSize = tileSize
                )
            }

        }
        allTiles.clear()
        collisionDetector.clear()
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
    suspend fun fetch(z: Int, x: Int, y: Int): Result<Map<String, ByteArray>> {
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

    suspend fun getTile(x: Int, y: Int, zoom: Double, size: Int): ImageBitmap {
        val pbfList = fetch(z = zoom.toInt(), x = x, y = y).getOrElse { e ->
            println("ERROR: ${e.message}")
            return emptyBitmap(size)
        }
        return render(pbfList = pbfList, zoom = zoom, tileSize = size)
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