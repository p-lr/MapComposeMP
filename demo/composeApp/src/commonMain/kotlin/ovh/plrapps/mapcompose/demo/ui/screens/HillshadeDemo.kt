package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.demo.viewmodels.HillshadeDemoVM
import ovh.plrapps.mapcompose.demo.viewmodels.HillshadePreset
import ovh.plrapps.mapcompose.ui.MapUI
import kotlin.math.roundToInt

expect object HillshadeDemo {
    @Composable
    fun Content()
}

/**
 * The map, plus a control for each of the two properties maplibre-gl-js#5768 added.
 *
 * `hillshade-method` picks one of five algorithms and `hillshade-illumination-altitude` is the
 * light's height above the horizon, which `basic`, `combined` and `multidirectional` read and
 * `standard` and `igor` ignore -- the slider is there to make that visible.
 */
@Composable
fun HillshadeCommonUi(screenModel: HillshadeDemoVM) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in HillshadePreset.entries) {
                FilterChip(
                    selected = preset == screenModel.preset,
                    onClick = { screenModel.onPresetSelected(preset) },
                    label = { Text(preset.label, fontSize = 12.sp) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("altitude ${screenModel.altitude.roundToInt()}°", fontSize = 12.sp)
            Slider(
                value = screenModel.altitude.toFloat(),
                onValueChange = { screenModel.onAltitudeChanged(it.toDouble()) },
                onValueChangeFinished = { screenModel.onAltitudeCommitted() },
                valueRange = 0f..90f,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        MapUI(
            Modifier,
            state = screenModel.state
        )
    }
}
