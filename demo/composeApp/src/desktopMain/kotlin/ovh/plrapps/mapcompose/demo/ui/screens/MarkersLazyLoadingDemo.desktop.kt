package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersLazyLoadingVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object MarkersLazyLoadingDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MarkersLazyLoadingVM() }

        MapWithZoomControl(state = screenModel.state) {
            MarkersLazyLoadingCommonUi(screenModel)
        }
    }
}