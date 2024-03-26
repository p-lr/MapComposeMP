package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

actual fun ByteArray.toImage(): ImageBitmap? {
    val img = Image.makeFromEncoded(this)
    val bitmap = Bitmap.makeFromImage(img)
    return bitmap.asComposeImageBitmap()
}