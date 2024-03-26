package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ovh.plrapps.mapcompose.demo.viewmodels.PathsVM
import ovh.plrapps.mapcompose.ui.MapUI
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen

object PathsDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { PathsVM() }

        MapUI(
            Modifier,
            state = screenModel.state
        )
    }
}