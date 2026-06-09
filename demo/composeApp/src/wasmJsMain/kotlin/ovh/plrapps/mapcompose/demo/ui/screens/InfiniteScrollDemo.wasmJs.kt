package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl
import ovh.plrapps.mapcompose.demo.viewmodels.InfiniteScrollVM

actual object InfiniteScrollDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { InfiniteScrollVM() }

        MapWithZoomControl(state = viewModel.state) {
            InfiniteScrollDemoCommonUi(viewModel)
        }
    }
}