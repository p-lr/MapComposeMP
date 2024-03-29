package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.ktor.client.HttpClient
import ovh.plrapps.mapcompose.demo.utils.getKtorClient
import ovh.plrapps.mapcompose.ui.state.MapState

actual object GlobalVM : ScreenModel {
    private val client = getKtorClient()

    actual var activeMapState: MapState? = null

    actual fun navigateTo(
        navigator: Navigator,
        screen: Screen
    ) {
        navigator.push(screen)
    }

    actual fun ktorClient(): HttpClient = client

    override fun onDispose() {
        client.close()
        super.onDispose()
    }
}