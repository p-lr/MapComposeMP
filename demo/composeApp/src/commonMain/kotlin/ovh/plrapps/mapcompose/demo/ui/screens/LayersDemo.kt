package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.demo.viewmodels.LayersVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object LayersDemoSimple {
    @Composable
    fun Content()
}

@Composable
fun LayersCommonUi(viewModel: LayersVM) {
    var slopesSliderValue by remember {
        mutableFloatStateOf(0.6f)
    }

    var roadSliderValue by remember {
        mutableFloatStateOf(1f)
    }

    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        MapUI(Modifier.weight(1f), state = viewModel.state)
        Spacer(Modifier.height(16.dp))
        LayerSlider(
            name = "Slopes",
            value = slopesSliderValue,
            onValueChange = {
                slopesSliderValue = it
                viewModel.setSlopesOpacity(it)
            }
        )
        LayerSlider(
            name = "Roads",
            value = roadSliderValue,
            onValueChange = {
                roadSliderValue = it
                viewModel.setRoadOpacity(it)
            }
        )
    }
}

@Composable
private fun LayerSlider(name: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(
        Modifier.height(50.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, Modifier.padding(end = 16.dp))
        Slider(
            value = value,
            onValueChange = onValueChange
        )
    }
}