package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.LayersVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object LayersDemoSimple : Screen

@Composable
fun LayersCommonUi(screenModel: LayersVM) {
    var satelliteSliderValue by remember {
        mutableFloatStateOf(1f)
    }

    var ignV2SliderValue by remember {
        mutableFloatStateOf(0.5f)
    }

    Column {
        BoxWithConstraints {
            MapUI(Modifier.size(maxWidth, maxHeight - 100.dp), state = screenModel.state)
        }
        LayerSlider(
            name = "Satellite",
            value = satelliteSliderValue,
            onValueChange = {
                satelliteSliderValue = it
                screenModel.setSatelliteOpacity(it)
            }
        )
        LayerSlider(
            name = "IGN v2",
            value = ignV2SliderValue,
            onValueChange = {
                ignV2SliderValue = it
                screenModel.setIgnV2Opacity(it)
            }
        )
    }
}

@Composable
private fun LayerSlider(name: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(Modifier.height(50.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = name, Modifier.padding(horizontal = 16.dp))
            Slider(
                value = value,
                onValueChange = onValueChange
            )
        }
    }
}