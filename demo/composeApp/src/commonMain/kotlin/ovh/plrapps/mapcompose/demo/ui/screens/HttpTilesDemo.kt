package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object HttpTilesDemo {
    @Composable
    fun Content()
}

@Composable
fun HttpTilesCommonUi(viewModel: HttpTilesVM) {
    MapUI(state = viewModel.state)
}