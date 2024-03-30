package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.CustomDrawVM
import ovh.plrapps.ui.MapContent

/**
 * This demo shows how to embed custom drawings inside [MapUI].
 */
actual object CustomDraw : Screen {
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { CustomDrawVM() }

        MapContent(state = screenModel.state) {
            View(screenModel)
        }
    }
}