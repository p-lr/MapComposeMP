package ovh.plrapps.mapcompose.vector.utils

/**
 * A least-recently-used map.
 *
 * **Not thread-safe, and `get` is a write**: it removes and reinserts the entry to record recency,
 * so two threads reading concurrently structurally mutate one `LinkedHashMap`. Every holder must
 * therefore confine it to one coroutine or guard it -- `VectorRasterizer` takes `pathCacheMutex`,
 * `tileCacheMutex`, `byteCacheMutex`, `rasterImageCacheMutex`, `demCacheMutex` and
 * `symbolBucketCacheMutex`, `TextLabelBuilder` takes its own `mutex`, and `PatternBrushCache` and
 * `TileRenderer.painters` are confined because a `TileRenderer` is built per `renderTile`.
 * `SpriteManager` is the one that could not be guarded -- its reader is not `suspend` -- and holds a
 * copy-on-write map instead.
 */
class LruCache<K, V>(maxSize: Int) {
    private val cache = LinkedHashMap<K, V>(0, 0.75f)

    /**
     * How many entries to keep.
     *
     * A `var` because a cache whose working set is decided by the viewport cannot be sized at
     * construction: see [growTo].
     */
    var maxSize: Int = maxSize
        private set

    /**
     * Raises [maxSize] to [size] if it is not already at least that, and never lowers it.
     *
     * A plain LRU evicts by recency alone, so one sized below its caller's *live* working set does
     * not just miss more often -- it evicts exactly what it is about to be asked for again, every
     * single pass. For the symbol bucket cache that meant a rebuilt `SymbolBucket` on every layout
     * run, and a rebuilt bucket is a bucket the cross-tile index has to re-derive identities for.
     */
    fun growTo(size: Int) {
        if (size > maxSize) maxSize = size
    }

    fun get(key: K): V? {
        val value = cache.remove(key)
        if (value != null) {
            cache[key] = value
        }
        return value
    }

    fun put(key: K, value: V) {
        cache.remove(key)
        while (cache.size >= maxSize) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            cache.remove(oldestKey)
        }
        cache[key] = value
    }

    fun remove(key: K): V? { return cache.remove(key) }

    fun clear() { cache.clear() }

    fun size(): Int = cache.size
}
