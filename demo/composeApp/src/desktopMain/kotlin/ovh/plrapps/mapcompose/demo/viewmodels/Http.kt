package ovh.plrapps.mapcompose.demo.viewmodels

import kotlinx.io.asSource
import ovh.plrapps.mapcompose.core.TileStreamProvider
import java.net.HttpURLConnection
import java.net.URL

actual fun makeHttpTileStreamProvider(): TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            val url =
                URL("https://raw.githubusercontent.com/p-lr/MapCompose/master/demo/src/main/assets/tiles/mont_blanc/$zoomLvl/$row/$col.jpg")
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