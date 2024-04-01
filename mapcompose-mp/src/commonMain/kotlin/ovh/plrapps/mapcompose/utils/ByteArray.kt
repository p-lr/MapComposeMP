package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.io.Source

expect fun Source.toImage(existing: ImageBitmap?, highFidelityColors: Boolean): ImageBitmap?