package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.VisibleAreaPaddingVM
import ovh.plrapps.ui.MapWithZoomControl

actual object VisibleAreaPaddingDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { VisibleAreaPaddingVM() }

        MapWithZoomControl(state = screenModel.state) {
            VisibleAreaPaddingCommonUi(screenModel)
        }
    }
}