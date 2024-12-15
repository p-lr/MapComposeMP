package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.centerOnMarker
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.map_marker

class CenteringOnMarkerVM(): ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    val state = MapState(4, 4096, 4096) {
        rotation(45f)
    }.apply {
        addLayer(tileStreamProvider)
        addMarker("parking", 0.2457938, 0.3746023) {
            Icon(
                painter = painterResource(Res.drawable.map_marker),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color(0xCC2196F3)
            )
        }
        enableRotation()
    }

    fun onCenter() {
        screenModelScope.launch {
            state.centerOnMarker("parking", destScale = 1f, destAngle = 0f)
        }
    }
}