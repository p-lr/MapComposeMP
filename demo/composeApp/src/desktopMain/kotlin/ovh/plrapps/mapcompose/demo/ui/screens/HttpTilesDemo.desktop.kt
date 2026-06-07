package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object HttpTilesDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { HttpTilesVM() }

        MapWithZoomControl(state = viewModel.state) {
            HttpTilesCommonUi(viewModel)
        }
    }
}