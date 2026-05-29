package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.LayersVM

actual object LayersDemoSimple {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { LayersVM() }

        LayersCommonUi(screenModel)
    }
}