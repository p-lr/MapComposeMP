package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import ovh.plrapps.mapcompose.demo.viewmodels.InfiniteScrollVM
import ovh.plrapps.mapcompose.demo.viewmodels.makeResTileStreamProvider

actual object InfiniteScrollDemo : Screen {
    @Composable
    actual override fun Content() {
        val appContext = LocalContext.current.applicationContext
        val screenModel = rememberScreenModel {
            /* For best performance, use a platform specific impl which return streams of tiles
             * The difference can be seen when profiling memory usage from AS with low overhead,
             * when comparing with SimpleDemoVM() */
            InfiniteScrollVM(makeResTileStreamProvider(appContext, "world"))
        }

        InfiniteScrollDemoCommonUi(screenModel)
    }
}