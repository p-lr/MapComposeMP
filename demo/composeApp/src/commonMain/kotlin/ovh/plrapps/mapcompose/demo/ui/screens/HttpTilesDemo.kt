package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object HttpTilesDemo {
    @Composable
    fun Content()
}

@Composable
fun HttpTilesCommonUi(screenModel: HttpTilesVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}