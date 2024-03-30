package ovh.plrapps.mapcompose.utils

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.io.Source
import kotlinx.io.asInputStream


actual fun Source.toImage(existing: ImageBitmap?): ImageBitmap? {
    val options = BitmapFactory.Options();
    options.inScaled = false
    options.inMutable = true
    options.inBitmap = existing?.asAndroidBitmap()

    return BitmapFactory.decodeStream(this.asInputStream(), null, options)?.asImageBitmap()
}