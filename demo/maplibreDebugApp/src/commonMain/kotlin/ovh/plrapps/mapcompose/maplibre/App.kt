package ovh.plrapps.mapcompose.maplibre

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import eu.wewox.lazytable.LazyTable
import eu.wewox.lazytable.LazyTableItem
import eu.wewox.lazytable.lazyTableDimensions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import mapcompose_mp.demo.maplibredebugapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.compose.rememberRasterizer
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.theme.AppTheme

@Composable
fun AsyncTile(
    x: Int,
    y: Int,
    zoom: Double,
    size: Int,
    tileProvider: suspend (x: Int, y: Int, zoom: Double, size: Int) -> ImageBitmap
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(x, y, zoom, size) {
        image = tileProvider(x, y, zoom, size)
    }

    if (image != null) {
        Box(
            Modifier.size(size.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap = image!!, contentDescription = null, modifier = Modifier.size(size.dp))
        }
    } else {
        // Placeholder
        Box(
            Modifier.size(size.dp).padding(1.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.LightGray)
            }
        }
    }
}


@Composable
fun DummyMapEngine(
    cfg: MapLibreConfiguration,
    tileProvider: suspend (x: Int, y: Int, zoom: Double, size: Int) -> ImageBitmap
) {
    var zoom by remember { mutableStateOf(3.0) }
    val gridSize = 2.0.pow(zoom).toInt().coerceAtLeast(1)
    val tileSize = 256.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minScreen = minOf(maxWidth, maxHeight)
        val dynamicTileSize = if (gridSize * 256.dp < minScreen) {
            minScreen / gridSize
        } else {
            tileSize
        }
        LazyTable(
            dimensions = lazyTableDimensions(
                columnsCount = gridSize,
                rowsCount = gridSize,
                columnSize = { dynamicTileSize },
                rowSize = { dynamicTileSize }
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = gridSize * gridSize,
                layoutInfo = {
                    LazyTableItem(
                        column = it % gridSize,
                        row = it / gridSize
                    )
                }
            ) { index ->
                val x = index % gridSize
                val y = index / gridSize
                AsyncTile(x = x, y = y, zoom = zoom, size = dynamicTileSize.value.toInt(), tileProvider = tileProvider)
            }
        }


        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x5A223750), shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    zoom = zoom + 1.0
                },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("zoom +")
            }
            Button(
                onClick = { if (zoom > 0.0) zoom = zoom - 1.0 }
            ) {
                Text("zoom -")
            }
            Text(
                text = "${(zoom * 100.0).toInt() / 100.0}",
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun App() {
    AppTheme {
        val rasterizer = rememberRasterizer(styleSource = {
            Res.readBytes("files/style_simple.json").decodeToString()
        })

        val cache = remember { mutableMapOf<String, ImageBitmap>() }
        Box(Modifier.fillMaxSize().background(Color.DarkGray))
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
}