package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import ovh.plrapps.mapcompose.demo.viewmodels.AddingMarkerVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM


object AddingMarkerDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { AddingMarkerVM() }
        val navigator = LocalNavigator.currentOrThrow
        val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }
        globalScreenModel.activeMapState = screenModel.state

        Column(Modifier.fillMaxSize()) {
            MapUI(
                Modifier.weight(2f),
                state = screenModel.state
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    screenModel.addMarker()
                }, Modifier.padding(8.dp)) {
                    Text(text = "Add marker")
                }

                Text("Drag markers with finger")
            }
        }
    }
}