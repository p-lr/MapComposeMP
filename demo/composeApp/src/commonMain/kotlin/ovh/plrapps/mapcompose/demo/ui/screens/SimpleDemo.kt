package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object MapDemoSimple {
    @Composable
    fun Content()
}

@Composable
fun MapSimpleCommonUi(viewModel: SimpleDemoVM) {
    MapUI(state = viewModel.state)
}