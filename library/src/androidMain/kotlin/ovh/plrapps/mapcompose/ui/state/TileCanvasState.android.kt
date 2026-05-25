package ovh.plrapps.mapcompose.ui.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import ovh.plrapps.mapcompose.core.Tile
import java.util.concurrent.Executors

internal actual fun Tile.sendToRecycle(recycleChannel: Channel<Tile>) {
    recycleChannel.trySend(this)
}

internal actual fun performRecycle(bitmap: ImageBitmap) {
    bitmap.asAndroidBitmap().recycle()
}

internal actual fun tileCanvasDispatcher(): CloseableCoroutineDispatcher {
    return Executors.newSingleThreadExecutor().asCoroutineDispatcher()
}
