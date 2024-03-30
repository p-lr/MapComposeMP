package ovh.plrapps.utils

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.ui.state.MapState

suspend fun zoomIn(state: MapState)  {
    state.scrollTo(
        state.centroidX,
        state.centroidY,
        state.scale * 1.5f,
        TweenSpec(800, easing = FastOutSlowInEasing)
    )
}

suspend fun zoomOut(state: MapState)  {
    state.scrollTo(
        state.centroidX,
        state.centroidY,
        state.scale / 1.5f,
        TweenSpec(800, easing = FastOutSlowInEasing)
    )
}