package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Offset
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.onTouchDown
import ovh.plrapps.mapcompose.api.rotateTo
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.demo.providers.makeTileStreamProvider
import ovh.plrapps.mapcompose.demo.ui.widgets.Marker
import ovh.plrapps.mapcompose.ui.state.MapState

/**
 * This demo shows how animations can be chained one after another.
 * Since animations APIs are suspending functions, this is easy to do.
 */
class AnimationDemoVM : ScreenModel {
    private val tileStreamProvider = makeTileStreamProvider()
    private var job: Job? = null
    private val spec = TweenSpec<Float>(2000, easing = FastOutSlowInEasing)

    val state = MapState(
        levelCount = 4,
        fullWidth = 4096,
        fullHeight = 4096,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    ).apply {
        addLayer(tileStreamProvider)
        shouldLoopScale = true
        enableRotation()
        addMarker("m0", 0.5, 0.5) { Marker() }
        addMarker("m1", 0.78, 0.78) { Marker() }
        addMarker("m2", 0.79, 0.79) { Marker() }
        addMarker("m3", 0.785, 0.72) { Marker() }
        onTouchDown {
            job?.cancel()
        }
        screenModelScope.launch {
            scrollTo(0.5, 0.5, 2f, SnapSpec())
        }
    }

    fun startAnimation() {
        /* Cancel ongoing animation */
        job?.cancel()

        /* Start a new one */
        with(state) {
            job = screenModelScope.launch {
                scrollTo(0.0, 0.0, 2f, spec, screenOffset = Offset.Zero)
                scrollTo(0.8, 0.8, 2f, spec)
                rotateTo(180f, spec)
                scrollTo(0.5, 0.5, 0.5f, spec)
                scrollTo(0.5, 0.5, 2f, TweenSpec(800, easing = FastOutSlowInEasing))
                rotateTo(0f, TweenSpec(1000, easing = FastOutSlowInEasing))
            }
        }
    }
}