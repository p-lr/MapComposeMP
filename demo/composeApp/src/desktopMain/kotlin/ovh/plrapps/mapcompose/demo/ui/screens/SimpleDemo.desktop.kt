package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object MapDemoSimple {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { SimpleDemoVM() }

        MapWithZoomControl(state = viewModel.state) {
                MapSimpleCommonUi(viewModel)
        }
    }
}