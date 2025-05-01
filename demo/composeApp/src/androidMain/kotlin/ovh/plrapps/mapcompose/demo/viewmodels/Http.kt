package ovh.plrapps.mapcompose.demo.viewmodels

import kotlinx.io.asSource
import ovh.plrapps.mapcompose.core.TileStreamProvider
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