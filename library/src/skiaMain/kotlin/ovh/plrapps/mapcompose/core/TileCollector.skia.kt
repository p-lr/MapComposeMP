package ovh.plrapps.mapcompose.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
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

internal actual fun makeWorkerData() = WorkerData

internal actual data object WorkerData

internal actual fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int,
    workerData: WorkerData
): ImageBitmap? {
    val bitmap = decodeImage(this.readByteArray(), subSamplingRatio) ?: return null
    /* With layers, overlays are drawn into this bitmap */
    if (!hasLayers) bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

internal actual fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int,
    workerData: WorkerData
): ImageBitmap? {
    val bitmap = decodeImage(this.readByteArray(), subSamplingRatio) ?: return null
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

internal actual fun processFinalImage(
    currentImage: ImageBitmap,
    previousLayer: ImageBitmap?
): ImageBitmap {
    currentImage.asSkiaBitmap().setImmutable()
    return currentImage
}

/**
 * Decoded bitmaps should be made immutable once nothing draws into them anymore. Otherwise, the
 * pixels are copied on every draw and the resulting image gets a new id, and the GPU texture
 * cache gets a cache miss => the tile is re-uploaded every frame.
 */
private fun decodeImage(bytes: ByteArray, subSamplingRatio: Int): Bitmap? {
    if (subSamplingRatio <= 1) return decodeFull(bytes)
    return decodeViaCodec(bytes, subSamplingRatio) ?: decodeViaSurface(bytes, subSamplingRatio)
}

private fun decodeFull(bytes: ByteArray): Bitmap? = runCatching {
    val img = Image.makeFromEncoded(bytes)
    try {
        Bitmap.makeFromImage(img)
    } finally {
        img.close()
    }
}.getOrNull()

private fun decodeViaCodec(bytes: ByteArray, ratio: Int): Bitmap? = runCatching {
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
            bitmap
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}.getOrNull()

private fun decodeViaSurface(bytes: ByteArray, ratio: Int): Bitmap? = runCatching {
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
                Bitmap.makeFromImage(snapshot)
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