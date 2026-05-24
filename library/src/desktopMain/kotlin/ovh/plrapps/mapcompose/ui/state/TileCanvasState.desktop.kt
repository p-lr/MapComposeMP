package ovh.plrapps.mapcompose.ui.state

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import ovh.plrapps.mapcompose.core.Tile
import java.util.concurrent.Executors

internal actual fun Tile.sendToRecycle(recycleChannel: Channel<Tile>) {
    // Empty on purpose
}

internal actual fun Tile.performRecycle() {
    // Empty on purpose
}

internal actual fun tileCanvasDispatcher(): CloseableCoroutineDispatcher {
    return Executors.newSingleThreadExecutor().asCoroutineDispatcher()
}