package ovh.plrapps.mapcompose.vector.data.extension

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual fun ImageBitmap.toBytes(): ByteArray? {
    val skiaBmp = this.asSkiaBitmap()

    return Image
        .makeFromBitmap(skiaBmp)
        .encodeToData(EncodedImageFormat.PNG)
        ?.bytes
}