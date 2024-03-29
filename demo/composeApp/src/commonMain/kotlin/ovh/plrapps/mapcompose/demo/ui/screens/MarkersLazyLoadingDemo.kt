package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM
import ovh.plrapps.mapcompose.ui.MapUI

object MarkersLazyLoadingDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MarkersLazyLoadingVM() }
        val navigator = LocalNavigator.currentOrThrow
        val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }
        globalScreenModel.activeMapState = screenModel.state

        MapUI(Modifier, state = screenModel.state)
    }
}