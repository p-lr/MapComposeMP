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
import ovh.plrapps.mapcompose.api.ExperimentalClusteringApi
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addLazyLoader
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.onMarkerClick
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.ui.state.markers.model.RenderingStrategy
import kotlin.random.Random

/**
 * Shows how to define and use a marker lazy-loader.
 */
@OptIn(ExperimentalClusteringApi::class, ExperimentalResourceApi::class)
class MarkersLazyLoadingVM() : ScreenModel {
    private val tileStreamProvider =
        ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider()

    val state = MapState(
        levelCount = 4,
        fullWidth = 4096,
        fullHeight = 4096,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ) {
        minimumScaleMode(Forced(1f))
        scale(1f)
        maxScale(4f)
        scroll(0.5, 0.5)
    }.apply {
        addLayer(tileStreamProvider)
        shouldLoopScale = true
    }

    init {
        /* Add a marker lazy loader. In this example, we use "default" for the id */
        state.addLazyLoader("default")

        repeat(200) { i ->
            val x = Random.nextDouble()
            val y = Random.nextDouble()

            /* Notice how we set the rendering strategy to lazy loading with the same id */
            state.addMarker(
                "marker-$i", x, y,
                renderingStrategy = RenderingStrategy.LazyLoading(lazyLoaderId = "default")
            ) {
                Icon(
                    painter = painterResource(Res.drawable.map_marker),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color(0xEE2196F3)
                )
            }
        }

        /* We can still add regular markers */
        state.addMarker(
            "marker-regular", 0.5, 0.5,
        ) {
            Icon(
                painter = painterResource(Res.drawable.map_marker),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color(0xEEF44336)
            )
        }

        state.onMarkerClick { id, x, y ->
            println("marker click $id $x $y")
        }
    }
}