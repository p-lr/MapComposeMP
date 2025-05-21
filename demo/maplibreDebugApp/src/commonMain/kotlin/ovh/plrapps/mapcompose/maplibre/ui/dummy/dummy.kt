package ovh.plrapps.mapcompose.maplibre.ui.dummy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import mapcompose_mp.demo.maplibredebugapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.compose.rememberRasterizer

@OptIn(ExperimentalResourceApi::class)
@Composable
fun DummyDebug() {
    val rasterizer = rememberRasterizer(styleSource = {
        Res.readBytes("files/style_simple.json").decodeToString()
    })
    val cache = remember { mutableMapOf<String, ImageBitmap>() }

    if (rasterizer != null) {
        DummyMapEngine(rasterizer.configuration) { x, y, zoom, size ->
            val key = "X$x|Y$y|Z$zoom|$size"
            cache.getOrPut(key = key) {
                // getTile suspend
                rasterizer.getTile(x, y, zoom, size * 2)
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(Color.DarkGray))
    }
}