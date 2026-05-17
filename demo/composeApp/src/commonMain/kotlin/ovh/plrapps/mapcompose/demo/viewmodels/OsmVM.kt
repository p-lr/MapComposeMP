package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import org.jetbrains.compose.resources.painterResource
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.demo.utils.latLonToNormalized
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.map_marker
import kotlin.math.pow

/**
 * Shows how to use WMTS tile servers with MapCompose, such as Open Street Map.
 */
class OsmVM : ScreenModel {
    private val tileStreamProvider = makeOsmTileStreamProvider()

    private val maxLevel = 16
    private val minLevel = 12
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = 256)
    private val paris = latLonToNormalized(48.856667, 2.351667) // Paris
    private val x = paris.first
    private val y = paris.second

    val state = MapState(levelCount = maxLevel + 1, mapSize, mapSize, workerCount = 16) {
        minimumScaleMode(Forced(1 / 2.0.pow(maxLevel - minLevel)))
        scroll(x, y)
        scale(0.0) // to zoom out initially
    }.apply {
        addLayer(tileStreamProvider)
        addMarker("id", x, y) {
            Icon(
                painter = painterResource(Res.drawable.map_marker),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color(0xCC2196F3)
            )
        }
    }
}

expect fun makeOsmTileStreamProvider(): TileStreamProvider

/**
 * wmts level are 0 based.
 * At level 0, the map corresponds to just one tile.
 */
private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
    return tileSize * 2.0.pow(wmtsLevel).toInt()
}