package ovh.plrapps.mapcompose.demo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ovh.plrapps.mapcompose.demo.viewmodels.InfiniteScrollVM
import ovh.plrapps.mapcompose.demo.viewmodels.makeResTileStreamProvider

actual object InfiniteScrollDemo {
    @Composable
    actual fun Content() {
        val appContext = LocalContext.current.applicationContext
        val viewModel = viewModel {
            /* For best performance, use a platform specific impl which return streams of tiles
             * The difference can be seen when profiling memory usage from AS with low overhead,
             * when comparing with SimpleDemoVM() */
            InfiniteScrollVM(makeResTileStreamProvider(appContext, "world"))
        }

        InfiniteScrollDemoCommonUi(viewModel)
    }
}