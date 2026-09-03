package ovh.plrapps.mapcompose.vector.data.extension

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * `getPixels` returns **non-premultiplied** ARGB whatever the bitmap's own premultiplication is,
 * which is exactly what [encodeBmp32] wants -- handing premultiplied values over as straight alpha
 * saturates every translucent pixel.
 *
 * The bitmap is always a software one here: it comes from `ImageBitmap(size, size)` in
 * `VectorRasterizer.renderTile`, never from the decoder's `Config.HARDWARE` path.
 */
actual fun ImageBitmap.toBytes(): ByteArray? {
    val bitmap = asAndroidBitmap()
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    return encodeBmp32(argb, bitmap.width, bitmap.height)
}
