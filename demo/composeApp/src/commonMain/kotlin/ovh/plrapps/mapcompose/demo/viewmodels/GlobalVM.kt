package ovh.plrapps.mapcompose.demo.viewmodels

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.ktor.client.HttpClient

expect object GlobalVM : ScreenModel {
    fun navigateTo(navigator: Navigator, screen: Screen)
    fun ktorClient(): HttpClient
}