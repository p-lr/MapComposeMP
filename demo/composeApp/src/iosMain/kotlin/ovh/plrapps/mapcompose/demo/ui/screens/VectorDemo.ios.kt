package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.VectorDemoVM

actual object VectorDemo {
    @Composable
    actual fun Content() {
        val viewModel = viewModel { VectorDemoVM() }

        VectorCommonUi(viewModel)
    }
}