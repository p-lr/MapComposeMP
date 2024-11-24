package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mapcompose_mp.demo.composeapp.generated.resources.Res
import mapcompose_mp.demo.composeapp.generated.resources.map_marker
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import ovh.plrapps.mapcompose.api.*
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState

class AddingMarkerVM : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    private var markerCount = 0

    val state = MapState(
        levelCount = 4,
        fullWidth = 4096,
        fullHeight = 4096,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ) {
        scale(0f) // zoom-out to minimum scale
    }.apply {
        addLayer(tileStreamProvider)
        onMarkerMove { id, x, y, _, _ ->
            println("move $id $x $y")
        }
        onMarkerClick { id, x, y ->
            println("marker click $id $x $y")
        }
        onMarkerLongPress { id, x, y ->
            println("on marker long press $id $x $y")
        }
        onTap { x, y ->
            println("on tap $x $y")
        }
        onLongPress { x, y ->
            println("on long press $x $y")
        }
        enableRotation()
        setScrollOffsetRatio(0.5f, 0.5f)
    }


    @OptIn(ExperimentalResourceApi::class)
    fun addMarker() {
        state.addMarker("marker$markerCount", 0.5, 0.5) {
            Icon(
                painter = painterResource(Res.drawable.map_marker),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color(0xCC2196F3)
            )
        }
        state.enableMarkerDrag("marker$markerCount")
        markerCount++
    }
}