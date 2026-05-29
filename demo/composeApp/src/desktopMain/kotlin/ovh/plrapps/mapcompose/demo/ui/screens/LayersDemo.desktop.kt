package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.LayersVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object LayersDemoSimple {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { LayersVM() }

        MapWithZoomControl(state = screenModel.state) {
            LayersCommonUi(screenModel)
        }
    }
}