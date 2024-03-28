package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.pow

/**
 * Shows how to use WMTS tile servers with MapCompose, such as Open Street Map.
 */
class OsmVM(httpClient: HttpClient) : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider(httpClient)

    private val maxLevel = 16
    private val minLevel = 12
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = 256)
    val state = MapState(levelCount = maxLevel + 1, mapSize, mapSize, workerCount = 16) {
        minimumScaleMode(Forced((1 / 2.0.pow(maxLevel - minLevel)).toFloat()))
        scroll(0.5064745545387268, 0.3440358340740204)  // Paris
    }.apply {
        addLayer(tileStreamProvider)
        scale = 0f  // to zoom out initially
    }
}

/**
 * A [TileStreamProvider] which performs HTTP requests.
 */
private fun makeTileStreamProvider(httpClient: HttpClient): TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        try {
            val response: HttpResponse =
                httpClient.get("https://tile.openstreetmap.org/$zoomLvl/$col/$row.png")
            response.readBytes()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * wmts level are 0 based.
 * At level 0, the map corresponds to just one tile.
 */
private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
    return tileSize * 2.0.pow(wmtsLevel).toInt()
}