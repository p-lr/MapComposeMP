package ovh.plrapps.mapcompose.utils

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun ByteArray.toImage(): ImageBitmap? {
    val options = BitmapFactory.Options();
    options.inScaled = false
    options.inMutable = true

    return BitmapFactory.decodeByteArray(this, 0, this.size, options)?.asImageBitmap()
}