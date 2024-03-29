package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.ktor.client.HttpClient
import ovh.plrapps.mapcompose.ui.state.MapState

expect object GlobalVM : ScreenModel {
    var activeMapState: MapState?

    fun navigateTo(navigator: Navigator, screen: Screen)
    fun ktorClient(): HttpClient
}