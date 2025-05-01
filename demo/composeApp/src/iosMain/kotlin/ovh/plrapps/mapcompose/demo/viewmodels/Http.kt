package ovh.plrapps.mapcompose.demo.viewmodels

import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import ovh.plrapps.mapcompose.demo.utils.readBuffer

actual fun makeHttpTileStreamProvider(): TileStreamProvider {
    // TODO: use a blocking http client. MapCompose already switches context when calling the provided TileStreamProvider,
    // so there is no need for an asynchronous http client such as Ktor.
    val httpClient = getKtorClient()
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            readBuffer(
                httpClient,
                path = "https://raw.githubusercontent.com/p-lr/MapCompose/master/demo/src/main/assets/tiles/mont_blanc_layered/$zoomLvl/$row/$col.jpg"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

actual fun makeOsmTileStreamProvider(): TileStreamProvider {
    // TODO: use a blocking http client. MapCompose already switches context when calling the provided TileStreamProvider,
    // so there is no need for an asynchronous http client such as Ktor.
    val httpClient = getKtorClient()
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            readBuffer(httpClient, "https://tile.openstreetmap.org/$zoomLvl/$col/$row.png")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}