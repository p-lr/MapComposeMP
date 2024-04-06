package ovh.plrapps.mapcompose.core

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * A pool of [ImageBitmap].
 * This class is thread-safe.
 */
internal class BitmapPool(coroutineContext: CoroutineContext) {
    private val mutex = Mutex()
    private val pool = mutableListOf<ImageBitmap>()
    private val receiveChannel = Channel<ImageBitmap>(capacity = UNLIMITED)
    private val scope = CoroutineScope(coroutineContext)

    init {
        scope.launch {
            for (b in receiveChannel) {
                mutex.withLock {
                    // TODO: dynamic threshold
                    if (pool.size < 30) {
                        pool.add(b)
                    }
                }
            }
        }
    }

    suspend fun getSize(): Int {
        return mutex.withLock { pool.size }
    }

    suspend fun get(): ImageBitmap? {
        mutex.withLock {
            if (pool.isEmpty()) {
                return null
            }
            return pool.removeFirstOrNull()
        }
    }

    /**
     * Don't make this method a suspending call. It causes ConcurrentModificationExceptions because
     * some collection iteration become interleaved.
     */
    fun put(b: ImageBitmap) {
        receiveChannel.trySend(b)
    }
}