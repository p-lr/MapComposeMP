package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.AddingMarkerVM

actual object AddingMarkerDemo {
    @Composable
    actual fun Content() {
        /* On Android the default factory can instantiate the ViewModel reflectively, so the
         * no-arg viewModel() works. Non-Android targets (iOS/desktop) have no such factory and
         * must build the instance explicitly via viewModel { AddingMarkerVM() }. */
        val viewModel: AddingMarkerVM = viewModel()

        AddingMarkerCommonUi(viewModel)
    }
}