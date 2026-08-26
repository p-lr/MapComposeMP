package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.HillshadeDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object HillshadeDemo {
    @Composable
    fun Content()
}

@Composable
fun HillshadeCommonUi(screenModel: HillshadeDemoVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}
