package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.OsmVM

actual object OsmDemo : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { OsmVM() }

        View(screenModel)
    }
}