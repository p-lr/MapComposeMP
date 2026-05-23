package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

actual fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    return decodeImage(this.readByteArray(), subSamplingRatio)
}

actual fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    return decodeImage(this.readByteArray(), subSamplingRatio)
}

actual fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap {
    return currentImage
}

private fun decodeImage(bytes: ByteArray, subSamplingRatio: Int): ImageBitmap? {
    if (subSamplingRatio <= 1) return decodeFull(bytes)
    return decodeViaCodec(bytes, subSamplingRatio) ?: decodeViaSurface(bytes, subSamplingRatio)
}

private fun decodeFull(bytes: ByteArray): ImageBitmap? = runCatching {
    val img = Image.makeFromEncoded(bytes)
    try {
        Bitmap.makeFromImage(img).asComposeImageBitmap()
    } finally {
        img.close()
    }
}.getOrNull()

private fun decodeViaCodec(bytes: ByteArray, ratio: Int): ImageBitmap? = runCatching {
    val data = Data.makeFromBytes(bytes)
    try {
        val codec = Codec.makeFromData(data)
        try {
            val targetWidth = (codec.width / ratio).coerceAtLeast(1)
            val targetHeight = (codec.height / ratio).coerceAtLeast(1)
            val info = ImageInfo(targetWidth, targetHeight, ColorType.N32, ColorAlphaType.PREMUL)
            val bitmap = Bitmap()
            check(bitmap.allocPixels(info)) { "allocPixels failed" }
            codec.readPixels(bitmap)
            bitmap.asComposeImageBitmap()
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}.getOrNull()

private fun decodeViaSurface(bytes: ByteArray, ratio: Int): ImageBitmap? = runCatching {
    val img = Image.makeFromEncoded(bytes)
    try {
        val targetWidth = (img.width / ratio).coerceAtLeast(1)
        val targetHeight = (img.height / ratio).coerceAtLeast(1)
        val info = ImageInfo(targetWidth, targetHeight, ColorType.N32, ColorAlphaType.PREMUL)
        val surface = Surface.makeRaster(info)
        try {
            surface.canvas.drawImageRect(
                img,
                Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                SamplingMode.LINEAR,
                null,
                true
            )
            val snapshot = surface.makeImageSnapshot()
            try {
                Bitmap.makeFromImage(snapshot).asComposeImageBitmap()
            } finally {
                snapshot.close()
            }
        } finally {
            surface.close()
        }
    } finally {
        img.close()
    }
}.getOrNull()
