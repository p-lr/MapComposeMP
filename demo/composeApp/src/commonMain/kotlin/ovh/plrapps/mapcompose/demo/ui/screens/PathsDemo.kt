package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import ovh.plrapps.mapcompose.demo.viewmodels.PathsVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object PathsDemo {
    @Composable
    fun Content()
}

@Composable
fun PathsCommonUi(viewModel: PathsVM) {
    MapUI(state = viewModel.state)
}