package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.demo.ui.widgets.Marker
import ovh.plrapps.mapcompose.ui.state.MapState

class VisibleAreaPaddingVM : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    val state = MapState(4, 8192, 8192) {
        scale(1.2)
    }.apply {
        enableRotation()
        addLayer(tileStreamProvider)
        addMarker("m0", 0.5, 0.5) { Marker() }
    }
}
