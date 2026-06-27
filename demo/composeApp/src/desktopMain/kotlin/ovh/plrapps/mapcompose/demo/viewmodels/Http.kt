package ovh.plrapps.mapcompose.demo.viewmodels

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.asSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import ovh.plrapps.mapcomposemp.demo.Res
import java.net.HttpURLConnection
import java.net.URL

/**
 * A [TileStreamProvider] which performs HTTP requests.
 */
actual fun makeHttpTileStreamProvider(): TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            val url =
                URL("https://raw.githubusercontent.com/p-lr/MapCompose/master/demo/src/main/assets/tiles/mont_blanc_layered/$zoomLvl/$row/$col.jpg")
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            connection.inputStream.asSource()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * A [TileStreamProvider] which queries OSM server.
 */
actual fun makeOsmTileStreamProvider() : TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            val url = URL("https://tile.openstreetmap.org/$zoomLvl/$col/$row.png")
            val connection = url.openConnection() as HttpURLConnection
            // OSM requires a user-agent
            connection.setRequestProperty("User-Agent", "Chrome/120.0.0.0 Safari/537.36")
            connection.doInput = true
            connection.connect()
            connection.inputStream.asSource()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

actual class OSMVectorTileStreamProvider actual constructor(actual override val styleUrl: String) :
    VectorTileStreamProvider {

    @OptIn(ExperimentalResourceApi::class)
    actual override suspend fun loadResources(url: String): RawSource? {
        return when (url) {
            "files/style_street_v2.json" -> {
                val buffer = Buffer()
                buffer.write(Res.readBytes(url))
                buffer
            }
            else -> getResourceAsStream(url)
        }
    }

    actual override suspend fun getTileStream(
        tileUrl: String,
        row: Int,
        col: Int,
        zoomLvl: Int
    ): RawSource? {
        return getResourceAsStream(tileUrl)
    }

    private fun getResourceAsStream(url: String): RawSource? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Chrome/120.0.0.0 Safari/537.36")
            // Explicitly request gzip — this disables Java's auto-decompression, so we
            // decompress manually below when the server confirms gzip encoding.
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.doInput = true
            connection.connect()

            val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                java.util.zip.GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            stream.asSource()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}