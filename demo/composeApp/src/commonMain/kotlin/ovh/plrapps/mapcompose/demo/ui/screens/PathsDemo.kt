package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.PathsVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object PathsDemo : Screen

@Composable
fun PathsDemo.View(screenModel: PathsVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}