package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

actual fun Source.toImage(existing: ImageBitmap?, highFidelityColors: Boolean): ImageBitmap? {
    return runCatching {
        val img = Image.makeFromEncoded(this.readByteArray())
        val bitmap = Bitmap.makeFromImage(img)
        bitmap.asComposeImageBitmap()
    }.getOrNull()
}