package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.setLayerOpacity
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcomposemp.demo.Res

class LayersVM() : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider(imageExt = ".jpg")
    private val slopesLayerProvider = makeTileStreamProvider("ign-slopes-", imageExt = ".png")
    private val roadLayerProvider = makeTileStreamProvider("ign-road-", imageExt = ".png")

    private var slopesId: String? = null
    private var roadId: String? = null

    val state = MapState(4, 8192, 8192).apply {
        shouldLoopScale = true
        enableRotation()
        screenModelScope.launch {
            scrollTo(0.4, 0.4, 1.0)
        }

        addLayer(tileStreamProvider)
        slopesId = addLayer(slopesLayerProvider, initialOpacity = 0.6f)
        roadId = addLayer(roadLayerProvider, initialOpacity = 1f)
    }

    fun setSlopesOpacity(opacity: Float) {
        slopesId?.also { id ->
            state.setLayerOpacity(id, opacity)
        }
    }

    fun setRoadOpacity(opacity: Float) {
        roadId?.also { id ->
            state.setLayerOpacity(id, opacity)
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
private fun makeTileStreamProvider(layer: String = "", imageExt: String) =
    TileStreamProvider { row, col, zoomLvl ->
        try {
            val buffer = Buffer()
            Res.readBytes("files/tiles/mont_blanc_layered/$zoomLvl/$row/$layer$col$imageExt").let {
                buffer.write(it)
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }