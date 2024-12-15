package ovh.plrapps.mapcompose.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.io.Source
import kotlinx.io.asInputStream


actual fun Source.toImage(existing: ImageBitmap?, highFidelityColors: Boolean): ImageBitmap? {
    val options = BitmapFactory.Options()
    options.inScaled = false
    options.inMutable = true
    options.inPreferredConfig = if (highFidelityColors) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

    /* Make a first attempt to reuse an existing Bitmap */
    return runCatching {
        options.inBitmap = existing?.asAndroidBitmap()
        BitmapFactory.decodeStream(this.asInputStream(), null, options)?.asImageBitmap()
    }.getOrElse {
        options.inBitmap = null
        BitmapFactory.decodeStream(this.asInputStream(), null, options)?.asImageBitmap()
    }
}