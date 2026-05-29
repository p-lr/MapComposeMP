package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersClusteringVM

actual object MarkersClusteringDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { MarkersClusteringVM() }

        MarkersClusteringCommonUi(screenModel)
    }
}