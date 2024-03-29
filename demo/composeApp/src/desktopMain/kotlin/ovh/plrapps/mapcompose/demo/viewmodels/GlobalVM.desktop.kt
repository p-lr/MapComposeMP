package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import ovh.plrapps.mapcompose.ui.state.MapState

actual object GlobalVM : ScreenModel {
    private val client = getKtorClient()

    actual var activeMapState: MapState? = null

    actual fun navigateTo(
        navigator: Navigator,
        screen: Screen
    ) {
        navigator.replaceAll(screen)
    }

    actual fun ktorClient(): HttpClient = client

    override fun onDispose() {
        client.close()
        super.onDispose()
    }

    fun zoomIn() {
        if (this.activeMapState != null) {
            val state = this.activeMapState!!
            screenModelScope.launch {
                state.scrollTo(state.centroidX, state.centroidY, state.scale * 1.5f, TweenSpec(800, easing = FastOutSlowInEasing))
            }
        }
    }

    fun zoomOut() {
        if (this.activeMapState != null) {
            val state = this.activeMapState!!
            screenModelScope.launch {
                state.scrollTo(state.centroidX, state.centroidY, state.scale / 1.5f, TweenSpec(800, easing = FastOutSlowInEasing))
            }
        }
    }
}