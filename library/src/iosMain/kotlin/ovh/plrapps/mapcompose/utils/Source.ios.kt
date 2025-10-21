package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

actual fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    return runCatching {
        val img = Image.makeFromEncoded(this.readByteArray())
        val bitmap = Bitmap.makeFromImage(img)
        bitmap.asComposeImageBitmap()
    }.getOrNull()
}

actual fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    return runCatching {
        val img = Image.makeFromEncoded(this.readByteArray())
        val bitmap = Bitmap.makeFromImage(img)
        bitmap.asComposeImageBitmap()
    }.getOrNull()
}

actual fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap {
    return currentImage
}