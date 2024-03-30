package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.demo.viewmodels.AddingMarkerVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object AddingMarkerDemo : Screen

@Composable
fun AddingMarkerCommonUi(screenModel: AddingMarkerVM) {
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