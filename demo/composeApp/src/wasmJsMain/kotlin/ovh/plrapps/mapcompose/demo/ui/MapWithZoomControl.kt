package ovh.plrapps.mapcompose.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.demo.utils.zoomIn
import ovh.plrapps.mapcompose.demo.utils.zoomOut

@Composable
fun MapWithZoomControl(state: MapState, content: @Composable () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        Column(modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 50.dp, end = 50.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        zoomIn(state)
                    }
                }
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        zoomOut(state)
                    }
                }
            ) {
                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
