package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.ui.MapContent

actual object HttpTilesDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { HttpTilesVM() }

        MapContent(state = screenModel.state) {
            View(screenModel)
        }
    }
}