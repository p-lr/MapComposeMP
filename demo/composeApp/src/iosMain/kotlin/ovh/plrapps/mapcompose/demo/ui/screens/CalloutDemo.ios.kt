package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.CalloutVM

actual object CalloutDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { CalloutVM() }

        CalloutCommonUi(screenModel)
    }
}