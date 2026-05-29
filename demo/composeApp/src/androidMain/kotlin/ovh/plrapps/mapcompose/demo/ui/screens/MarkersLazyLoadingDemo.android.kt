package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM

actual object MarkersLazyLoadingDemo {
    @Composable
    actual fun Content() {
        val screenModel: MarkersLazyLoadingVM = viewModel()

        MarkersLazyLoadingCommonUi(screenModel)
    }
}