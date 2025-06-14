package ovh.plrapps.mapcompose.maplibre.ui.mapcompose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.ui.MapUI

@Composable
fun MapLibreDebug() {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minScreen = minOf(maxWidth, maxHeight)

        val vModel = viewModel(
            key = "MapComposeEngineViewModel",
            modelClass = MapComposeEngineViewModel::class,
            factory = viewModelFactory {
                initializer {
                    MapComposeEngineViewModel(
                        density = density,
                        fontFamilyResolver = fontFamilyResolver,
                        textMeasurer = textMeasurer,
                        initialViewPort = minScreen,
                    )
                }
            }
        )

        val zoom by vModel.zoom.collectAsState()
        val symbols by vModel.symbols.collectAsState()
        val symbolsBitmap by vModel.symbolsBitmap.collectAsState()
        val collisionLayerIsVisible by vModel.collisionLayerIsVisible.collectAsState()

        LaunchedEffect(minScreen) {
            vModel.updateTileSizeForScreen(minScreen)
        }

        MapUI(modifier = Modifier.fillMaxSize(), state = vModel.state)

        if (collisionLayerIsVisible) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw a semi-transparent background for the viewport
                drawRect(
                    color = Color(0x40a7f895),
                    size = size,
                )
                
                // Display debug bitmap with symbols if it exists
                symbolsBitmap?.let { bitmap ->
                    // Center the bitmap in the canvas
                    val offsetX = (size.width - bitmap.width) / 2f
                    val offsetY = (size.height - bitmap.height) / 2f
                    
                    drawImage(
                        image = bitmap,
                        topLeft = androidx.compose.ui.geometry.Offset(offsetX, offsetY)
                    )
                    
                    // Draw a frame around the bitmap for clarity
                    drawRect(
                        color = Color.Cyan,
                        topLeft = androidx.compose.ui.geometry.Offset(offsetX, offsetY),
                        size = androidx.compose.ui.geometry.Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = collisionLayerIsVisible, onCheckedChange = { vModel.setCollisionLayerIsVisible(it) })
                Text(
                    text = "Collision layer",
                    style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                )
            }
            Button(
                onClick = {
                    scope.launch { zoomIn(vModel.state) }
                },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text("zoom +")
            }
            Button(
                onClick = {
                    scope.launch { zoomOut(vModel.state) }
                }
            ) {
                Text("zoom -")
            }
            Text(
                text = "Z: ${(zoom * 100.0).toInt() / 100.0}",
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
            Text(
                text = "S: ${symbols.size}",
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
        }
    }
}