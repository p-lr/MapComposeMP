package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mapcompose_mp.demo.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.api.addCallout
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addPath
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.onPathClick
import ovh.plrapps.mapcompose.api.onPathLongPress
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.demo.ui.widgets.Callout
import ovh.plrapps.mapcompose.ui.paths.PathDataBuilder
import ovh.plrapps.mapcompose.ui.paths.model.PatternItem
import ovh.plrapps.mapcompose.ui.paths.model.PatternItem.Dash
import ovh.plrapps.mapcompose.ui.paths.model.PatternItem.Gap
import ovh.plrapps.mapcompose.ui.state.MapState

/**
 * In this sample, we add "tracks" to the map. The tracks are rendered as paths using MapCompose.
 */
class PathsVM() : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()

    val state = MapState(
        levelCount = 4,
        fullWidth = 4096,
        fullHeight = 4096,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ).apply {
        addLayer(tileStreamProvider)
        shouldLoopScale = true
        enableRotation()

        /**
         * Demonstrates path click.
         */
        onPathClick { id, x, y ->
            var shouldAnimate by mutableStateOf(true)
            addCallout(
                id, x, y,
                absoluteOffset = DpOffset(0.dp, (-10).dp),
            ) {
                Callout(x, y, title = "Click on $id", shouldAnimate) {
                    shouldAnimate = false
                }
            }
        }

        /**
         * Demonstrates path long-press.
         */
        onPathLongPress { id, x, y ->
            var shouldAnimate by mutableStateOf(true)
            addCallout(
                id, x, y,
                absoluteOffset = DpOffset(0.dp, (-10).dp),
            ) {
                Callout(x, y, title = "Long-press on $id", shouldAnimate) {
                    shouldAnimate = false
                }
            }
        }

        screenModelScope.launch {
            scrollTo(0.72, 0.3)
        }
    }


    init {
        /* Add tracks */
        addTrack("track1", Color(0xFF448AFF))
        addTrack("track2", Color(0xFFFFFF00))
        addTrack("track3", pattern = listOf(Dash(8.dp), Gap(4.dp)))

        // filled polygon
        with(state) {
            addPath(
                id = "filled polygon",
                color = Color.Green,
                fillColor = Color.Green.copy(alpha = .6f),
                clickable = true,
            ) {
                addPoint(0.14, 0.15)
                addPoint(0.14, 0.18)
                addPoint(0.28, 0.10)
            }
        }
    }

    /**
     * In this sample, we retrieve track points from text files in the assets.
     * To add a path, use the [addPath] api. From inside the builder block, you can add individual
     * points or a list of points.
     * Here, since we're getting points from a sequence, we add them on the fly using [PathDataBuilder.addPoint].
     */
    @OptIn(ExperimentalResourceApi::class)
    private fun addTrack(
        trackName: String,
        color: Color? = null,
        pattern: List<PatternItem>? = null
    ) {
        screenModelScope.launch {
            val lines = Res.readBytes("files/tracks/$trackName.txt").decodeToString().lineSequence()
            state.addPath(
                id = trackName, color = color, clickable = true, pattern = pattern
            ) {
                for (line in lines) {
                    val values = line.split(',').map(String::toDouble)
                    addPoint(values[0], values[1])
                }
            }
        }
    }
}