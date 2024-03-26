package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

actual fun dpToPx(dp: Float): Float = with(density) { dp.dp.toPx() } //TODO

var density = Density(1f)