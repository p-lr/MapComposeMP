package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.addVectorLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.pow

/**
 * Shows the `hillshade` layer over a `raster-dem` source.
 *
 * The elevation comes from AWS Terrain Tiles, which are Terrarium-encoded and need no API key. The
 * style bundles nothing but a background and the hillshade itself, so what is on screen is entirely
 * the relief -- which is the point of the screen. It starts over the Alps.
 */
class HillshadeDemoVM : ViewModel() {
    private val vectorTileStreamProvider = OSMVectorTileStreamProvider(
        styleUrl = "files/style_hillshade.json"
    )

    private val maxLevel = 13
    private val minLevel = 4
    private val tileSize = 256
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = tileSize)

    val state = MapState(
        levelCount = maxLevel + 1,
        fullWidth = mapSize,
        fullHeight = mapSize,
        workerCount = 16,
        tileSize = tileSize,
    ) {
        minimumScaleMode(Forced(1 / 2.0.pow(maxLevel - minLevel)))
        scroll(0.5231943373, 0.3542287256)
    }.apply {
        viewModelScope.launch {
            addVectorLayer(vectorTileStreamProvider)
        }
        enableRotation()
        scale = 0.0
        shouldLoopScale = true
    }
}

/**
 * wmts level are 0 based.
 * At level 0, the map corresponds to just one tile.
 */
private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
    return tileSize * 2.0.pow(wmtsLevel).toInt()
}
