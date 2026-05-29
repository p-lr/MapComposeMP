package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.VisibleAreaPaddingVM

actual object VisibleAreaPaddingDemo {
    @Composable
    actual fun Content() {
        val screenModel = viewModel { VisibleAreaPaddingVM() }

        VisibleAreaPaddingCommonUi(screenModel)
    }
}