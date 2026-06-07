package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.RotationVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object RotationDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { RotationVM() }

        MapWithZoomControl(state = viewModel.state) {
            RotationCommonUi(viewModel)
        }
    }
}