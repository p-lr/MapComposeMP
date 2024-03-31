package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.CalloutVM
import ovh.plrapps.mapcompose.demo.ui.MapWithZoomControl

actual object CalloutDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { CalloutVM() }

        MapWithZoomControl(state = screenModel.state) {
            CalloutCommonUi(screenModel)
        }
    }
}