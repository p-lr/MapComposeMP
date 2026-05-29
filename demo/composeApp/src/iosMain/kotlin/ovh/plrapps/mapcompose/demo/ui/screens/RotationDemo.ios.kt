package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.RotationVM

actual object RotationDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { RotationVM() }

        RotationCommonUi(screenModel)
    }
}