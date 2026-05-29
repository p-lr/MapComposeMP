package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersClusteringVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object MarkersClusteringDemo {
    @Composable
    fun Content()
}

@Composable
fun MarkersClusteringCommonUi(screenModel: MarkersClusteringVM) {
    MapUI(Modifier, state = screenModel.state)
}