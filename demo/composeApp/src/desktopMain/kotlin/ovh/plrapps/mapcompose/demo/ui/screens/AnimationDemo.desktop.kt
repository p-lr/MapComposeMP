package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.AnimationDemoVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object AnimationDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { AnimationDemoVM() }

        MapWithZoomControl(state = viewModel.state) {
            AnimationCommonUi(viewModel)
        }
    }
}