package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import org.jetbrains.compose.resources.painterResource
import ovh.plrapps.mapcompose.api.ExperimentalClusteringApi
import ovh.plrapps.mapcompose.api.addClusterer
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.ui.state.markers.model.RenderingStrategy
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.map_marker


/**
 * Shows how to define and use a marker clusterer.
 */
@OptIn(ExperimentalClusteringApi::class)
class MarkersClusteringVM : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    val state = MapState(4, 4096, 4096) {
        scale(0.2)
        maxScale(8.0)
        scroll(0.5, 0.5)
    }.apply {
        addLayer(tileStreamProvider)
    }

    init {
        /* Add a marker clusterer to manage markers. In this example, we use "default" for the id */
        state.addClusterer("default") { ids ->
            { Cluster(size = ids.size) }
        }

        /* Add some markers to the map, using the same clusterer id we just defined (if a marker
         * is added without any clusterer, it won't be managed by any clusterer)*/
        listOf(
            0.5 to 0.5,
            0.51 to 0.5,
            0.5 to 0.54,
            0.51 to 0.54,
            0.6 to 0.52,
            0.48 to 0.35,
            0.48 to 0.355,
            0.485 to 0.35,
            0.52 to 0.35,
            0.515 to 0.36,
            0.515 to 0.355,
        ).forEachIndexed { i, pair ->
            state.addMarker(
                id = "marker-$i",
                x = pair.first,
                y = pair.second,
                renderingStrategy = RenderingStrategy.Clustering("default"),
            ) {
                Marker()
            }
        }

        /* We can still add regular markers */
        state.addMarker(
            "marker-regular", 0.52, 0.36,
            clickable = false
        ) {
            Icon(
                painter = painterResource(Res.drawable.map_marker),
                contentDescription = null,
                modifier = Modifier.size(50.dp),
                tint = Color(0xEEF44336)
            )
        }
    }

    @Composable
    private fun Marker() {
        Icon(
            painter = painterResource(Res.drawable.map_marker),
            contentDescription = null,
            modifier = Modifier.size(50.dp),
            tint = Color(0xEE2196F3)
        )
    }

    @Composable
    private fun Cluster(size: Int) {
        /* Here we can customize the cluster style */
        Box(
            modifier = Modifier
                .background(
                    Color(0x992196F3),
                    shape = CircleShape
                )
                .size(50.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = size.toString(), color = Color.White)
        }
    }
}
