package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object MarkersLazyLoadingDemo {
    @Composable
    fun Content()
}

@Composable
fun MarkersLazyLoadingCommonUi(viewModel: MarkersLazyLoadingVM) {
    MapUI(state = viewModel.state)
}