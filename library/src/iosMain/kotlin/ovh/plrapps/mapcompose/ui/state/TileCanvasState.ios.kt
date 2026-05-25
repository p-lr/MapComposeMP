package ovh.plrapps.mapcompose.ui.state

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.newSingleThreadContext
import ovh.plrapps.mapcompose.core.Tile

internal actual fun Tile.sendToRecycle(recycleChannel: Channel<Tile>) {
    // Empty on purpose
}

internal actual fun performRecycle(bitmap: ImageBitmap) {
    // Empty on purpose
}

@OptIn(DelicateCoroutinesApi::class)
internal actual fun tileCanvasDispatcher(): CloseableCoroutineDispatcher {
    return newSingleThreadContext("TileCanvasThread")
}
