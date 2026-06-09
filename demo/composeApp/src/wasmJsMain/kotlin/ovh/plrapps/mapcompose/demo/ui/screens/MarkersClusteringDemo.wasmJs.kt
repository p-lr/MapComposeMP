package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersClusteringVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object MarkersClusteringDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { MarkersClusteringVM() }

        MapWithZoomControl(state = viewModel.state) {
            MarkersClusteringCommonUi(viewModel)
        }
    }
}