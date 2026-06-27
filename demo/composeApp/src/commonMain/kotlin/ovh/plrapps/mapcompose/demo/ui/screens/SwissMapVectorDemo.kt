package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.SwissMapVectorDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object SwissMapVectorDemo {
    @Composable
    fun Content()
}

@Composable
fun SwissMapVectorCommonUi(screenModel: SwissMapVectorDemoVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}