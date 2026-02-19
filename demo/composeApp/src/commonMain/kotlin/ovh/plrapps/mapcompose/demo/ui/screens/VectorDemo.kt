package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.VectorDemoVM
import ovh.plrapps.mapcompose.ui.MapUI

expect object VectorDemo : Screen

@Composable
fun VectorCommonUi(screenModel: VectorDemoVM) {
    MapUI(
        Modifier,
        state = screenModel.state
    )
}