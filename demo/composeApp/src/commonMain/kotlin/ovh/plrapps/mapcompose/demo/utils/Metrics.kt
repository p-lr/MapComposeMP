package ovh.plrapps.mapcompose.demo.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Convert px to dp
 */
@Composable
fun pxToDp(px: Int): Dp = LocalDensity.current.run { px.toDp() }

/**
 * Convert dp to px
 */
@Composable
fun dpToPx(dp: Int): Int = LocalDensity.current.run { dp.dp.toPx().toInt() }