package ovh.plrapps.mapcompose.utils

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

actual fun Source.toImage(existing: ImageBitmap?, highFidelityColors: Boolean): ImageBitmap? {
    // TODO: find a way to set ColorType depending on highFidelityColors flag
    val img = Image.makeFromEncoded(this.readByteArray())
    val bitmap = Bitmap.makeFromImage(img)
    return bitmap.asComposeImageBitmap()
}
