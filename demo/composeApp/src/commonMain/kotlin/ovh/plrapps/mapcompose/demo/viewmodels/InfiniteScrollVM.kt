package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.demo.providers.makeWorldTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState

class InfiniteScrollVM(
    /* By default, use the simple platform agnostic TileStreamProvider.
     * Here, we pass the TileStreamProvider in the constructor so we can easily provide a
     * a platform specific implementation. This is useful for performance checks. */
    private val tileStreamProvider: TileStreamProvider = makeWorldTileStreamProvider()
) : ScreenModel {
    val state = MapState(5, 8192, 8192) {
        scale(0.1)
        infiniteScrollX(true)
    }.apply {
        addLayer(tileStreamProvider)
        shouldLoopScale = true
        enableRotation()
    }
}

