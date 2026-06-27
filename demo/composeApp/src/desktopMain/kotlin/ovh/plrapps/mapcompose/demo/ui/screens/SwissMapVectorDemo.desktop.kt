package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl
import ovh.plrapps.mapcompose.demo.viewmodels.SwissMapVectorDemoVM

actual object SwissMapVectorDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { SwissMapVectorDemoVM() }

        MapWithZoomControl(state = viewModel.state) {
            SwissMapVectorCommonUi(viewModel)
        }
    }
}