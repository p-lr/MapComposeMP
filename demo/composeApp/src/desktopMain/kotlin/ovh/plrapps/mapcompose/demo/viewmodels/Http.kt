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
        openHttpStream(
            "https://raw.githubusercontent.com/p-lr/MapCompose/master/demo/src/main/assets/tiles/mont_blanc_layered/$zoomLvl/$row/$col.jpg"
        )
    }
}

/**
 * A [TileStreamProvider] which queries OSM server.
 */
actual fun makeOsmTileStreamProvider() : TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        // OSM requires a user-agent
        openHttpStream("https://tile.openstreetmap.org/$zoomLvl/$col/$row.png", USER_AGENT)
    }
}

actual class OSMVectorTileStreamProvider actual constructor(actual override val styleUrl: String) :
    VectorTileStreamProvider {

    @OptIn(ExperimentalResourceApi::class)
    actual override suspend fun loadResources(url: String): RawSource? {
        return when {
            /* Any bundled style resource, so a new demo style needs no change here. */
            url.startsWith("files/") -> {
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

    /* Requests gzip explicitly, which disables Java's auto-decompression: [openHttpStream]
     * decompresses when the server confirms the encoding. */
    private fun getResourceAsStream(url: String): RawSource? =
        openHttpStream(url, USER_AGENT, acceptGzip = true)
}

private const val USER_AGENT = "Chrome/120.0.0.0 Safari/537.36"

/**
 * The body of a GET on [url], or null when there is none.
 *
 * The connection is released on every path that does not hand the body over -- a non-2xx response,
 * which a vector source answers for every tile outside its coverage, and any failure.
 * `connection.inputStream` throws on an error status, so asking for it first skipped both the error
 * body and `disconnect()` and leaked the connection. On success the caller closes the returned
 * source, which releases it.
 */
private fun openHttpStream(url: String, userAgent: String? = null, acceptGzip: Boolean = false): RawSource? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            userAgent?.let { setRequestProperty("User-Agent", it) }
            if (acceptGzip) setRequestProperty("Accept-Encoding", "gzip")
            // Without these a stalled request holds its connection forever.
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
        }
        val status = connection.responseCode
        if (status !in 200..299) {
            if (status != HttpURLConnection.HTTP_NOT_FOUND) println("HTTP $status for $url")
            connection.errorStream?.close()
            connection.disconnect()
            return null
        }

        val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
            java.util.zip.GZIPInputStream(connection.inputStream)
        } else {
            connection.inputStream
        }
        stream.asSource()
    } catch (e: Exception) {
        connection?.disconnect()
        println("HTTP request failed for $url: ${e.message}")
        null
    }
}
