package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.PathsVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object PathsDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { PathsVM() }

        MapWithZoomControl(state = viewModel.state) {
            PathsCommonUi(viewModel)
        }
    }
}