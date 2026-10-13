package ovh.plrapps.mapcompose.vector.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One load per key at a time, however many callers ask for it at once.
 *
 * The tile workers, the neighbour gathers of circle and heatmap layers and the symbol layout pass all
 * ask for the same tiles at the same moment -- a new viewport is exactly when all three start -- and
 * each used to fetch and decode on its own. A second caller now awaits the first one's load.
 *
 * The load runs in [scope], not in the caller: one caller going away -- a tile that stopped being
 * wanted -- must not cancel the load the others are waiting on, and the bytes it fetches are worth
 * caching anyway. A caller that is cancelled stops *waiting*, nothing more.
 */
internal class SharedLoads<V>(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val running = HashMap<String, Deferred<V>>()

    suspend fun load(key: String, block: suspend () -> V): V {
        val deferred = mutex.withLock {
            running.getOrPut(key) {
                scope.async {
                    try {
                        block()
                    } finally {
                        mutex.withLock { running.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }
}
