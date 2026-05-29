package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object HttpTilesDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { HttpTilesVM() }

        MapWithZoomControl(state = screenModel.state) {
            HttpTilesCommonUi(screenModel)
        }
    }
}