package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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

expect object RotationDemo {
    @Composable
    fun Content()
}

@Composable
fun RotationCommonUi(viewModel: RotationVM) {
    val sliderValue = viewModel.state.rotation / 360f

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        MapUI(
            Modifier.weight(1f),
            state = viewModel.state
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.onRotate() },
                Modifier.padding(8.dp)
            ) {
                Text(text = "Rotate 90°")
            }
            Slider(
                value = sliderValue,
                valueRange = 0f..0.9999f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                onValueChange = { v -> viewModel.state.rotation = v * 360f })
        }
    }
}