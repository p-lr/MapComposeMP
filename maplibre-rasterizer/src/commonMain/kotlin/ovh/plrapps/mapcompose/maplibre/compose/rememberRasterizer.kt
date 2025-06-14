package ovh.plrapps.mapcompose.maplibre.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.MapLibreRasterizer
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.getMapLibreConfiguration
import kotlin.math.roundToInt

@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberRasterizer(styleSource: suspend () -> String): MapLibreRasterizer? {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val textMeasurer = rememberTextMeasurer()
    val configuration by produceState<MapLibreConfiguration?>(null) {
        value = styleSource().let {
            getMapLibreConfiguration(it, pixelRatio = density.density.roundToInt()).getOrThrow()
        }
    }

    return remember(configuration) {
        configuration?.let {
            MapLibreRasterizer(
                configuration = it,
                density = density,
                fontFamilyResolver = fontFamilyResolver,
                textMeasurer = textMeasurer,
                tileCache = null
            )
        }
    }
}