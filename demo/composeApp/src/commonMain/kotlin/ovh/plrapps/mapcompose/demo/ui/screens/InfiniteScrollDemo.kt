package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import ovh.plrapps.mapcompose.demo.viewmodels.InfiniteScrollVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object InfiniteScrollDemo {
    @Composable
    fun Content()
}

@Composable
fun InfiniteScrollDemoCommonUi(viewModel: InfiniteScrollVM) {
    MapUI(state = viewModel.state)
}