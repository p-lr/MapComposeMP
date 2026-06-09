package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.CalloutVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object CalloutDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { CalloutVM() }

        MapWithZoomControl(state = viewModel.state) {
            CalloutCommonUi(viewModel)
        }
    }
}