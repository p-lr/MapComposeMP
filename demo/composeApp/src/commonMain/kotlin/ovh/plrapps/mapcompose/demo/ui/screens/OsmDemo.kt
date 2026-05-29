package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.demo.viewmodels.OsmVM

expect object OsmDemo {
    @Composable
    fun Content()
}

@Composable
fun OsmCommonUi(screenModel: OsmVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}