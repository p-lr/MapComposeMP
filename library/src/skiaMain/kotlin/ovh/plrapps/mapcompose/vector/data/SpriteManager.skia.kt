package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Skia stores its raster surfaces **premultiplied**, so [argb] -- which is straight alpha, as
 * `Color.toArgb()` produces -- has to be multiplied through here.
 *
 * Leaving it out does not make a pixel merely a little wrong: Skia blends a premultiplied source as
 * `src.rgb + dst.rgb * (1 - a)`, so a halo sample at a quarter alpha contributes its colour at full
 * strength and clamps to white. That is what turned a label's `text-halo-color` into an opaque slab
 * rather than the outline the distance field's soft edge describes.
 */
internal actual fun imageBitmapFromArgb(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(
        width = width,
        height = height,
        colorType = ColorType.BGRA_8888,
        alphaType = ColorAlphaType.PREMUL
    )

    val bytes = ByteArray(width * height * 4)
    var j = 0
    for (px in argb) {
        val a = (px ushr 24) and 0xFF
        if (a == 0) {
            // Nothing to write: the array is already zeroed.
            j += 4
            continue
        }
        if (a == 255) {
            bytes[j++] = (px and 0xFF).toByte()           // B
            bytes[j++] = ((px ushr 8) and 0xFF).toByte()  // G
            bytes[j++] = ((px ushr 16) and 0xFF).toByte() // R
        } else {
            bytes[j++] = premultiply(px and 0xFF, a)           // B
            bytes[j++] = premultiply((px ushr 8) and 0xFF, a)  // G
            bytes[j++] = premultiply((px ushr 16) and 0xFF, a) // R
        }
        bytes[j++] = a.toByte()                                // A
    }

    val skiaImage = Image.makeRaster(info, bytes, width * 4)
    return skiaImage.toComposeImageBitmap()
}

/** `channel * alpha / 255`, rounded rather than truncated. */
private fun premultiply(channel: Int, alpha: Int): Byte = ((channel * alpha + 127) / 255).toByte()

internal actual fun byteArrayToImageBitmap(bytes: ByteArray): ImageBitmap {
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
