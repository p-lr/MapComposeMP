package ovh.plrapps.mapcompose.demo.viewmodels

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import ovh.plrapps.mapcompose.demo.utils.readBuffer
import ovh.plrapps.mapcomposemp.demo.Res

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

actual class OSMVectorTileStreamProvider actual constructor(actual override val styleUrl: String) :
    VectorTileStreamProvider {
    val httpClient = getKtorClient()

    @OptIn(ExperimentalResourceApi::class)
    actual override suspend fun loadResources(url: String): RawSource? {
        return try {
            when (url) {
                "files/style_street_v2.json" -> {
                    val buffer = Buffer()
                    buffer.write(Res.readBytes(url))
                    buffer
                }
                else -> readBuffer(httpClient, url)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    actual override suspend fun getTileStream(
        tileUrl: String,
        row: Int,
        col: Int,
        zoomLvl: Int
    ): RawSource? {
        return try {
            readBuffer(httpClient, tileUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}