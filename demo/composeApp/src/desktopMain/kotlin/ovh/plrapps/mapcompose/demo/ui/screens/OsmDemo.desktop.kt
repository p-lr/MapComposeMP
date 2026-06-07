package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.OsmVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object OsmDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { OsmVM() }

        MapWithZoomControl(state = viewModel.state) {
            OsmCommonUi(viewModel)
        }
    }
}