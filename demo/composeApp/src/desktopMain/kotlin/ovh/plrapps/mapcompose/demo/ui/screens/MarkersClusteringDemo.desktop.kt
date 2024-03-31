package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.MarkersClusteringVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object MarkersClusteringDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MarkersClusteringVM() }

        MapWithZoomControl(state = screenModel.state) {
            MarkersClusteringCommonUi(screenModel)
        }
    }
}