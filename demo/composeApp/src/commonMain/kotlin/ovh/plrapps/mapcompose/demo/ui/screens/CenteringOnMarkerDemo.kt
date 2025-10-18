package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ovh.plrapps.mapcompose.demo.viewmodels.CenteringOnMarkerVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object CenteringOnMarkerDemo : Screen

@Composable
fun CenteringOnMarkerCommonUi(screenModel: CenteringOnMarkerVM) {
    val onCenter: () -> Unit = screenModel::onCenter

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        MapUI(
            Modifier.weight(2f),
            state = screenModel.state
        )
        Button(onClick = {
            onCenter()
        }, Modifier.padding(8.dp)) {
            Text(text = "Center on marker")
        }
    }
}