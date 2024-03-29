package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import ovh.plrapps.mapcompose.demo.viewmodels.AnimationDemoVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM


object AnimationDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { AnimationDemoVM() }
        val navigator = LocalNavigator.currentOrThrow
        val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }
        globalScreenModel.activeMapState = screenModel.state

        val onRestart: () -> Unit = screenModel::startAnimation

        Column(Modifier.fillMaxSize()) {
            MapUI(
                Modifier.weight(2f),
                state = screenModel.state
            )
            Button(onClick = {
                onRestart()
            }, Modifier.padding(8.dp)) {
                Text(text = "Start")
            }
        }
    }
}
