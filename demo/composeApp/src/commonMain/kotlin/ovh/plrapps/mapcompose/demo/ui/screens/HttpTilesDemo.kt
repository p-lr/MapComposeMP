package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.HttpTilesVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen

object HttpTilesDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { HttpTilesVM() }

        MapUI(
            Modifier,
            state = screenModel.state
        )
    }
}