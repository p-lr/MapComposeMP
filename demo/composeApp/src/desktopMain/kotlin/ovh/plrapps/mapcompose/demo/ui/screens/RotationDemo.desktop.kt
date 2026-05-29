package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.RotationVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object RotationDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { RotationVM() }

        MapWithZoomControl(state = screenModel.state) {
            RotationCommonUi(screenModel)
        }
    }
}