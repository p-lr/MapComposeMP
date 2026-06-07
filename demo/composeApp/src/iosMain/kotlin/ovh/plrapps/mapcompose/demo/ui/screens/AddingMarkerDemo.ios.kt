package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.AddingMarkerVM

actual object AddingMarkerDemo {
    @Composable
    actual fun Content() {
        /* Non-Android targets have no reflective ViewModel factory, so the instance must be
         * constructed explicitly here — unlike Android, which can use the no-arg viewModel(). */
        val viewModel = viewModel { AddingMarkerVM() }

        AddingMarkerCommonUi(viewModel)
    }

}