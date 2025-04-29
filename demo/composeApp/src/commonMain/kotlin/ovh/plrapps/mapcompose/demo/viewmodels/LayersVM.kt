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
    private val tileStreamProvider =
        makeTileStreamProvider("mont_blanc")
    private val satelliteProvider =
        makeTileStreamProvider("mont_blanc_satellite")
    private val ignV2Provider =
        makeTileStreamProvider("mont_blanc_ignv2")

    private var satelliteId: String? = null
    private var ignV2Id: String? = null

    val state = MapState(4, 4096, 4096).apply {
        shouldLoopScale = true
        enableRotation()
        screenModelScope.launch {
            scrollTo(0.5, 0.5, 1.0)
        }

        addLayer(tileStreamProvider)
        satelliteId = addLayer(satelliteProvider)
        ignV2Id = addLayer(ignV2Provider, 0.5f)
    }

    @OptIn(ExperimentalResourceApi::class)
    private fun makeTileStreamProvider(folder: String) =
        TileStreamProvider { row, col, zoomLvl ->
            try {
                val buffer = Buffer()
                Res.readBytes("files/tiles/$folder/$zoomLvl/$row/$col.jpg").let {
                    buffer.write(it)
                    buffer
                }
            } catch (e: Exception) {
                null
            }
        }

    fun setSatelliteOpacity(opacity: Float) {
        satelliteId?.also { id ->
            state.setLayerOpacity(id, opacity)
        }

    }

    fun setIgnV2Opacity(opacity: Float) {
        ignV2Id?.also { id ->
            state.setLayerOpacity(id, opacity)
        }
    }
}