package ovh.plrapps.mapcompose.ui.state

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import ovh.plrapps.mapcompose.core.Tile
import kotlin.coroutines.CoroutineContext

internal actual fun Tile.sendToRecycle(recycleChannel: Channel<Tile>) {
    // Empty on purpose
}

internal actual fun Tile.performRecycle() {
    // Empty on purpose
}

/**
 * WASM/JS is single-threaded, so a truly dedicated OS thread is impossible.
 * Fall back to a sequential view of [Dispatchers.Default].
 */
internal actual fun tileCanvasDispatcher(): CloseableCoroutineDispatcher =
    object : CloseableCoroutineDispatcher() {
        private val delegate = Dispatchers.Default.limitedParallelism(1)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, block)
        }
        override fun close() {}
    }