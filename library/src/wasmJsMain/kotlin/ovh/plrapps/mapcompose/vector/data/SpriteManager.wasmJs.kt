package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

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
        bytes[j++] = (px and 0xFF).toByte()           // B
        bytes[j++] = ((px ushr 8) and 0xFF).toByte()  // G
        bytes[j++] = ((px ushr 16) and 0xFF).toByte() // R
        bytes[j++] = ((px ushr 24) and 0xFF).toByte() // A
    }

    val skiaImage = Image.makeRaster(info, bytes, width * 4)
    return skiaImage.toComposeImageBitmap()
}

internal actual fun byteArrayToImageBitmap(bytes: ByteArray): ImageBitmap {
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}