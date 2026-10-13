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

    private fun getResourceAsStream(url: String): RawSource? = openHttpStream(url, USER_AGENT)
}

private const val USER_AGENT = "Chrome/120.0.0.0 Safari/537.36"

/**
 * The body of a GET on [url], or null when there is none.
 *
 * The connection is released on every path that does not hand the body over: a non-2xx response
 * -- a vector source answers 404 for every tile outside its coverage, which around Switzerland is
 * most of the viewport at low zoom -- and any failure. `connection.inputStream` *throws* on an
 * error status, so asking for it first, as this used to, skipped both the error body and
 * `disconnect()`, and every such request left a connection and its TLS buffers allocated: ~2000 of
 * them, 130 MB of heap, before the app ran out of memory. On the success path the caller closes the
 * returned source, which releases the connection.
 */
private fun openHttpStream(url: String, userAgent: String? = null): RawSource? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            userAgent?.let { setRequestProperty("User-Agent", it) }
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
        connection.inputStream.asSource()
    } catch (e: Exception) {
        connection?.disconnect()
        println("HTTP request failed for $url: ${e.message}")
        null
    }
}