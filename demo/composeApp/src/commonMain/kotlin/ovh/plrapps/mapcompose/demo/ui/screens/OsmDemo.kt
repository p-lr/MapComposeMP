package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.OsmVM

expect object OsmDemo : Screen

@Composable
fun OsmCommonUi(screenModel: OsmVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}