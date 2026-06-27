package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.api.addVectorLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.pow

/**
 * Shows how MapCompose behaves with remote HTTP tiles.
 */
class VectorDemoVM : ViewModel() {
    private val vectorTileStreamProvider = OSMVectorTileStreamProvider(
        styleUrl = "files/style_street_v2.json"
    )

    private val maxLevel = 16
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = 256)

    val state = MapState(
        levelCount = maxLevel + 1,
        fullWidth = mapSize,
        fullHeight = mapSize,
        workerCount = 16
    ){
        infiniteScrollX(true)
    }.apply {
        viewModelScope.launch {
            addVectorLayer(vectorTileStreamProvider)
        }
        enableRotation()
        scale = 0.0
        shouldLoopScale = true
    }
}

expect class OSMVectorTileStreamProvider(styleUrl: String) : VectorTileStreamProvider {
    override val styleUrl: String

    override suspend fun loadResources(url: String): RawSource?
    override suspend fun getTileStream(
        tileUrl: String,
        row: Int,
        col: Int,
        zoomLvl: Int
    ): RawSource?
}

/**
 * wmts level are 0 based.
 * At level 0, the map corresponds to just one tile.
 */
private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
    return tileSize * 2.0.pow(wmtsLevel).toInt()
}