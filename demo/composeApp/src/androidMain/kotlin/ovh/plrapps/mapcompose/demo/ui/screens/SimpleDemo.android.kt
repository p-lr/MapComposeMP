package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.SimpleDemoVM
import ovh.plrapps.mapcompose.demo.viewmodels.makeResTileStreamProvider

actual object MapDemoSimple {
    @Composable
    actual fun Content() {
        val appContext = LocalContext.current.applicationContext
        val screenModel = viewModel {
            /* For best performance, use a platform specific impl which return streams of tiles
             * The difference can be seen when profiling memory usage from AS with low overhead,
             * when comparing with SimpleDemoVM() */
            SimpleDemoVM(makeResTileStreamProvider(appContext, "mont_blanc_layered"))
        }

        MapSimpleCommonUi(screenModel)
    }
}