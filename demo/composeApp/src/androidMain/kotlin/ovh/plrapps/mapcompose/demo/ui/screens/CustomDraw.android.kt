package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.CustomDrawVM

/**
 * This demo shows how to embed custom drawings inside [MapUI].
 */
actual object CustomDraw {
    @Composable
    actual fun Content() {
        val viewModel: CustomDrawVM = viewModel()

        CustomDrawCommonUi(viewModel)
    }
}