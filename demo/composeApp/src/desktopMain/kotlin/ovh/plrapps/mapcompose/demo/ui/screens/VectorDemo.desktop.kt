package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl
import ovh.plrapps.mapcompose.demo.viewmodels.VectorDemoVM

actual object VectorDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { VectorDemoVM() }

        MapWithZoomControl(state = screenModel.state) {
            VectorCommonUi(screenModel)
        }
    }
}