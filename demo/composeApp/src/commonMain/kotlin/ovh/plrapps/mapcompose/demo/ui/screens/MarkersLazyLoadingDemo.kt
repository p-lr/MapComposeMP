package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.ui.MapUI

expect object MarkersLazyLoadingDemo : Screen

@Composable
fun MarkersLazyLoadingDemo.View(screenModel: MarkersLazyLoadingVM) {
    MapUI(Modifier, state = screenModel.state)
}