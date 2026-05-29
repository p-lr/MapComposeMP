package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.OsmVM

actual object OsmDemo {
    @Composable
    actual fun Content() {
        val screenModel: OsmVM = viewModel()

        OsmCommonUi(screenModel)
    }
}