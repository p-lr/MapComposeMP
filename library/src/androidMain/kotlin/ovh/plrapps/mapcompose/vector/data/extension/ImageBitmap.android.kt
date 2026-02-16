package ovh.plrapps.mapcompose.vector.data.extension

import androidx.compose.ui.graphics.ImageBitmap

actual fun ImageBitmap.toBytes(): ByteArray? {
    return this.toBytes()
}