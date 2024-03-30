package ovh.plrapps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.utils.zoomIn
import ovh.plrapps.utils.zoomOut

@Composable
fun MapWithZoomControl(state: MapState, content: @Composable () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        Column(modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 10.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Button(
                modifier = Modifier.size(40.dp),
                onClick = {
                    coroutineScope.launch {
                        zoomIn(state)
                    }
                }
            ) {
                Text("+")
            }
            Button(
                modifier = Modifier.size(40.dp),
                onClick = {
                    coroutineScope.launch {
                        zoomOut(state)
                    }
                }
            ) {
                Text("-")
            }
        }
    }
}