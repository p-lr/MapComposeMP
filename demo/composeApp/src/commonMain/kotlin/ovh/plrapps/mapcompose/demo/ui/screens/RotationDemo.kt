package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import ovh.plrapps.mapcompose.api.rotation
import ovh.plrapps.mapcompose.demo.viewmodels.RotationVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ovh.plrapps.mapcompose.demo.viewmodels.GlobalVM

object RotationDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RotationVM() }
        val navigator = LocalNavigator.currentOrThrow
        val globalScreenModel = navigator.rememberNavigatorScreenModel { GlobalVM }
        globalScreenModel.activeMapState = screenModel.state

        val sliderValue = screenModel.state.rotation / 360f

        Column(Modifier.fillMaxSize()) {
            MapUI(
                Modifier.weight(2f),
                state = screenModel.state
            )
            Row {
                Button(onClick = { screenModel.onRotate() }, Modifier.padding(8.dp)) {
                    Text(text = "Rotate 90°")
                }
                Slider(
                    value = sliderValue,
                    valueRange = 0f..0.9999f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    onValueChange = { v -> screenModel.state.rotation = v * 360f })
            }
        }
    }
}