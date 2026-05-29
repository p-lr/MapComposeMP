package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.CenteringOnMarkerVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object CenteringOnMarkerDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { CenteringOnMarkerVM() }

        MapWithZoomControl(state = screenModel.state) {
            CenteringOnMarkerCommonUi(screenModel)
        }
    }
}