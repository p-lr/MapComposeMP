package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.LayersVM
import ovh.plrapps.ui.MapContent

actual object LayersDemoSimple : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { LayersVM() }

        MapContent(state = screenModel.state) {
            View(screenModel)
        }
    }
}