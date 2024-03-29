package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import io.ktor.client.HttpClient
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.demo.utils.getStream
import ovh.plrapps.mapcompose.ui.state.MapState

/**
 * Shows how MapCompose behaves with remote HTTP tiles.
 */
class HttpTilesVM() : ScreenModel {
    private val httpClient = getKtorClient()
    private val tileStreamProvider = makeTileStreamProvider(httpClient)

    val state = MapState(
        levelCount = 4,
        fullWidth = 4096,
        fullHeight = 4096,
        workerCount = 16  // Notice how we increase the worker count when performing HTTP requests
    ).apply {
        addLayer(tileStreamProvider)
        scale = 0f
        shouldLoopScale = true
    }

    override fun onDispose() {
        httpClient.close()
        super.onDispose()
    }
}

/**
 * A [TileStreamProvider] which performs HTTP requests.
 */
private fun makeTileStreamProvider(httpClient: HttpClient) =
    TileStreamProvider { row, col, zoomLvl ->
        try {
            getStream(
                httpClient,
                path = "https://raw.githubusercontent.com/p-lr/MapCompose/master/demo/src/main/assets/tiles/mont_blanc/$zoomLvl/$row/$col.jpg"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }