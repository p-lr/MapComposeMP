package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.VisibleAreaPaddingVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object VisibleAreaPaddingDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { VisibleAreaPaddingVM() }

        MapWithZoomControl(state = viewModel.state) {
            VisibleAreaPaddingCommonUi(viewModel)
        }
    }
}