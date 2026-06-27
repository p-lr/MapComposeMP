package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.VectorDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object VectorDemo {
    @Composable
    fun Content()
}

@Composable
fun VectorCommonUi(screenModel: VectorDemoVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}