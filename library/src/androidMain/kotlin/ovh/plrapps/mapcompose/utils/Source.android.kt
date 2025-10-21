package ovh.plrapps.mapcompose.utils

import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.io.Source
import kotlinx.io.asInputStream


actual fun Source.decodeFirstLayer(
    hasLayers: Boolean,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    val bitmapOptions = BitmapFactory.Options()
    bitmapOptions.inSampleSize = subSamplingRatio
    if (hasLayers) {
        bitmapOptions.inMutable = true
        bitmapOptions.inPreferredConfig = if (optimizeForLowEndDevices) {
            Config.RGB_565
        } else {
            Config.ARGB_8888
        }
    } else {
        if (canUseHardwareBitmaps()) {
            bitmapOptions.inPreferredConfig = Config.HARDWARE
        } else {
            bitmapOptions.inMutable = true
            bitmapOptions.inPreferredConfig = if (optimizeForLowEndDevices) {
                Config.RGB_565
            } else {
                Config.ARGB_8888
            }
        }
    }

    return runCatching {
        BitmapFactory.decodeStream(this.asInputStream(), null, bitmapOptions)
    }.getOrNull()?.asImageBitmap()
}

actual fun Source.decodeOverlay(
    previousLayer: ImageBitmap?,
    tileSize: Int,
    optimizeForLowEndDevices: Boolean,
    subSamplingRatio: Int
): ImageBitmap? {
    val bitmapOptions = BitmapFactory.Options()
    bitmapOptions.inSampleSize = subSamplingRatio
    bitmapOptions.inBitmap = previousLayer?.asAndroidBitmap()
    bitmapOptions.inMutable = true
    bitmapOptions.inPreferredConfig = if (optimizeForLowEndDevices) {
        Config.RGB_565
    } else {
        Config.ARGB_8888
    }
    return runCatching {
        BitmapFactory.decodeStream(this.asInputStream(), null, bitmapOptions)
    }.getOrNull()?.asImageBitmap()
}

actual fun processFinalImage(currentImage: ImageBitmap, previousLayer: ImageBitmap?): ImageBitmap {
    return if (canUseHardwareBitmaps()) {
        val bitmap = currentImage.asAndroidBitmap()
        bitmap.copy(Config.HARDWARE, false).asImageBitmap().also {
            /* Since we copied to hardware bitmap, be can recycle */
            bitmap.recycle()
            previousLayer?.asAndroidBitmap()?.recycle()
        }
    } else currentImage
}

/**
 * On Android O+, ART has a more efficient GC and HARDWARE Bitmaps are supported, making
 * Bitmap re-use much less important.
 * However:
 * - a framework issue pre Q requires to wait until GL context is initialized. Otherwise,
 * allocating a hardware Bitmap can cause a native crash.
 * - Allocating a hardware Bitmap involves the creation of a file descriptor. Android O, as well
 * as some P devices, have a maximum of 1024 file descriptors. Android Q+ devices have a much
 * higher limit of fd.
 *
 * To avoid all those issues entirely, we enable HARDWARE Bitmaps on Android Q and above.
 * We don't monitor the file descriptor count because in practice, MapCompose creates a few
 * hundreds of them and they seem to be efficiently recycled.
 */
private fun canUseHardwareBitmaps(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}