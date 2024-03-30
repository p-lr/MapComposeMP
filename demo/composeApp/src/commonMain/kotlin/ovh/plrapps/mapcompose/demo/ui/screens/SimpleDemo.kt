package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.screen.Screen

expect object MapDemoSimple : Screen

@Composable
fun MapSimpleCommonUi(screenModel: SimpleDemoVM) {
    MapUI(modifier = Modifier, state = screenModel.state)
}