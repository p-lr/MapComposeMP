package ovh.plrapps.mapcompose.vector.utils

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
