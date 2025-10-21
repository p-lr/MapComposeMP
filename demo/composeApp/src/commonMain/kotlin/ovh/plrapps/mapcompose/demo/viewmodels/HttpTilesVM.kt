package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState

/**
 * Shows how MapCompose behaves with remote HTTP tiles.
 */
class HttpTilesVM : ScreenModel {
    private val tileStreamProvider = makeHttpTileStreamProvider()

    val state = MapState(
        levelCount = 4,
        fullWidth = 8192,
        fullHeight = 8192,
        workerCount = 16  // Notice how we increase the worker count when performing HTTP requests
    ).apply {
        addLayer(tileStreamProvider)
        scale = 0.0
        shouldLoopScale = true
    }
}

expect fun makeHttpTileStreamProvider(): TileStreamProvider
