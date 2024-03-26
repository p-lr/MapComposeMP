package ovh.plrapps.mapcompose.demo.ui.widgets

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mapcompose_mp.demo.composeapp.generated.resources.Res
import mapcompose_mp.demo.composeapp.generated.resources.map_marker
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalResourceApi::class)
@Composable
fun Marker() = Icon(
    painter = painterResource(Res.drawable.map_marker),
    contentDescription = null,
    modifier = Modifier.size(50.dp),
    tint = Color(0xCC2196F3)
)