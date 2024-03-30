package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object HttpTilesDemo : Screen

@Composable
fun HttpTilesDemo.View(screenModel: HttpTilesVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}