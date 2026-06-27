package ovh.plrapps.mapcompose.vector.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap

internal actual fun imageBitmapFromArgb(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bmp = createBitmap(width, height)
    bmp.setPixels(argb, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}

internal actual fun byteArrayToImageBitmap(bytes: ByteArray): ImageBitmap {
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}