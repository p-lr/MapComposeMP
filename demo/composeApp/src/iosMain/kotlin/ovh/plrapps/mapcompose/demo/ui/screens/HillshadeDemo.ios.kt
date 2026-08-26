package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.HillshadeDemoVM

actual object HillshadeDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { HillshadeDemoVM() }

        HillshadeCommonUi(viewModel)
    }
}
