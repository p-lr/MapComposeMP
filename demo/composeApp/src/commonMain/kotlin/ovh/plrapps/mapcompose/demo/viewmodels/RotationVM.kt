package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.rotateTo
import ovh.plrapps.mapcompose.api.rotation
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scroll
import ovh.plrapps.mapcompose.api.setScrollOffsetRatio
import ovh.plrapps.mapcompose.api.setStateChangeListener
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState

class RotationVM : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    val state = MapState(4, 8192, 8192).apply {
        addLayer(tileStreamProvider)
        enableRotation()
        setScrollOffsetRatio(0.3f, 0.3f)
        scale = 0.0

        /* Not useful here, just showing how this API works */
        setStateChangeListener {
            println("scale: $scale, scroll: $scroll, rotation: $rotation")
        }
    }

    fun onRotate() {
        screenModelScope.launch {
            state.rotateTo(state.rotation + 90f)
        }
    }
}