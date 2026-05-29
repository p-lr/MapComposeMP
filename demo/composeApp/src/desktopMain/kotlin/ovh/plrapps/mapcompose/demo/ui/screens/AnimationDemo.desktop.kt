package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.AnimationDemoVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object AnimationDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { AnimationDemoVM() }

        MapWithZoomControl(state = screenModel.state) {
            AnimationCommonUi(screenModel)
        }
    }
}