package ovh.plrapps.mapcompose.demo.ui.widgets

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import ovh.plrapps.mapcomposemp.demo.Res
import ovh.plrapps.mapcomposemp.demo.map_marker

@Composable
fun Marker() = Icon(
    painter = painterResource(Res.drawable.map_marker),
    contentDescription = null,
    modifier = Modifier.size(50.dp),
    tint = Color(0xCC2196F3)
)