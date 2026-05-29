package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM

actual object MapDemoSimple {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { SimpleDemoVM() }

        MapSimpleCommonUi(screenModel)
    }
}