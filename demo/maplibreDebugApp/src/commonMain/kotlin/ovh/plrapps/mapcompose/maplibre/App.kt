package ovh.plrapps.mapcompose.maplibre

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.theme.AppTheme
import ovh.plrapps.mapcompose.maplibre.ui.mapcompose.MapLibreDebug

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun App() {
    AppTheme {
         MapLibreDebug()
    }
}