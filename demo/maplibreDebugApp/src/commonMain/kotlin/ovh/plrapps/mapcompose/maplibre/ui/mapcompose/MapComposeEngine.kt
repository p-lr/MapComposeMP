package ovh.plrapps.mapcompose.maplibre.ui.mapcompose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    BoxWithConstraints(Modifier.fillMaxSize().border(2.dp, Color.Green)) {
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
                        initialViewPort = minScreen
                    )
                }
            }
        )

        val zoom by vModel.zoom.collectAsState()

        LaunchedEffect(minScreen) {
            vModel.updateTileSizeForScreen(minScreen)
        }

        MapUI(modifier = Modifier.fillMaxSize(), state = vModel.state)

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
                text = "${(zoom * 100.0).toInt() / 100.0}",
                style = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            )
        }
    }
}