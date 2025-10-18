package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.api.rotation
import ovh.plrapps.mapcompose.demo.viewmodels.RotationVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object RotationDemo : Screen

@Composable
fun RotationCommonUi(screenModel: RotationVM) {
    val sliderValue = screenModel.state.rotation / 360f

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        MapUI(
            Modifier.weight(2f),
            state = screenModel.state
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
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