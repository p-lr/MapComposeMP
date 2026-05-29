package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object MarkersLazyLoadingDemo {
    @Composable
    fun Content()
}

@Composable
fun MarkersLazyLoadingCommonUi(screenModel: MarkersLazyLoadingVM) {
    MapUI(Modifier, state = screenModel.state)
}