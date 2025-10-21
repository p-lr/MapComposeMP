package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.io.Source

expect fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap?

expect fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap?

expect fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap
