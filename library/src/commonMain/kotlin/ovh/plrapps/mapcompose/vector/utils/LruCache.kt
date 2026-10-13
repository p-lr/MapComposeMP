package ovh.plrapps.mapcompose.vector.utils

/**
 * A least-recently-used map, bounded by entry count and, optionally, by weight.
 *
 * **Not thread-safe, and `get` is a write**: it removes and reinserts the entry to record recency,
 * so two threads reading concurrently structurally mutate one `LinkedHashMap`. Every holder must
 * therefore confine it to one coroutine or guard it -- `VectorRasterizer` takes `pathCacheMutex`,
 * `tileCacheMutex`, `byteCacheMutex`, `rasterImageCacheMutex`, `demCacheMutex` and
 * `symbolBucketCacheMutex`, `TextLabelBuilder` takes its own `mutex`, and `PatternBrushCache` and
 * `TileRenderer.painters` are confined because a `TileRenderer` is built per `renderTile`.
 * `SpriteManager` is the one that could not be guarded -- its reader is not `suspend` -- and holds a
 * copy-on-write map instead.
 *
 * With a [weigher], entries are also evicted, oldest first, until their summed weight is within
 * [maxWeight] -- which is what a cache of tile bytes or bitmaps has to be bounded by, since one
 * entry can be a hundred times the size of another. An entry heavier than [maxWeight] on its own is
 * still kept, alone, rather than refused: the caller asked for it and is about to use it.
 */
class LruCache<K, V>(
    maxSize: Int,
    val maxWeight: Long = Long.MAX_VALUE,
    private val weigher: ((V) -> Long)? = null,
) {
    private val cache = LinkedHashMap<K, V>(0, 0.75f)

    /** The summed weight of every entry; always 0 without a [weigher]. */
    var weight: Long = 0L
        private set

    /**
     * How many entries to keep.
     *
     * A `var` because a cache whose working set is decided by the viewport cannot be sized at
     * construction: see [growTo] and [shrinkTo].
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

    /**
     * Lowers [maxSize] to [size], evicting the oldest entries beyond it. Never raises it.
     *
     * The counterpart of [growTo] for a working set that has shrunk -- a window made smaller, a
     * map zoomed in -- so the size a large viewport once needed is not kept forever.
     */
    fun shrinkTo(size: Int) {
        if (size >= maxSize) return
        maxSize = size.coerceAtLeast(1)
        trim(keep = 0)
    }

    fun get(key: K): V? {
        val value = cache.remove(key)
        if (value != null) {
            cache[key] = value
        }
        return value
    }

    fun put(key: K, value: V) {
        cache.remove(key)?.let { weight -= weigh(it) }
        trim(keep = 1)
        cache[key] = value
        weight += weigh(value)
        // The entry just added is never evicted by its own weight; see the class KDoc.
        trimWeight(protect = key)
    }

    fun remove(key: K): V? {
        val value = cache.remove(key) ?: return null
        weight -= weigh(value)
        return value
    }

    fun clear() {
        cache.clear()
        weight = 0L
    }

    fun size(): Int = cache.size

    /** Every entry, oldest first. A snapshot: mutating the cache afterwards does not affect it. */
    fun values(): List<V> = cache.values.toList()

    private fun weigh(value: V): Long = weigher?.invoke(value) ?: 0L

    /** Evicts the oldest entries until there is room for [keep] more under [maxSize]. */
    private fun trim(keep: Int) {
        while (cache.size + keep > maxSize) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            remove(oldestKey)
        }
    }

    private fun trimWeight(protect: K) {
        if (weigher == null) return
        while (weight > maxWeight && cache.size > 1) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            if (oldestKey == protect) break
            remove(oldestKey)
        }
    }
}
