package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM

object HttpTilesDemo : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }
        val screenModel = rememberScreenModel { HttpTilesVM(globalScreenModel.ktorClient()) }
        globalScreenModel.activeMapState = screenModel.state

        MapUI(
            Modifier,
            state = screenModel.state
        )
    }
}