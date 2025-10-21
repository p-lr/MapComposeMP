package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM
import ovh.plrapps.mapcompose.demo.viewmodels.makeResTileStreamProvider

actual object MapDemoSimple : Screen {
    @Composable
    override fun Content() {
        val appContext = LocalContext.current.applicationContext
        val screenModel = rememberScreenModel {
            /* For best performance, use a platform specific impl which return streams of tiles
             * The difference can be seen when profiling memory usage from AS with low overhead,
             * when comparing with SimpleDemoVM() */
            SimpleDemoVM(makeResTileStreamProvider(appContext))
        }

        MapSimpleCommonUi(screenModel)
    }
}