package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.CalloutVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object CalloutDemo : Screen

@Composable
fun CalloutDemo.View(screenModel: CalloutVM) {
    Column(Modifier.fillMaxSize()) {
        MapUI(
            Modifier.weight(2f),
            state = screenModel.state
        )
    }
}
