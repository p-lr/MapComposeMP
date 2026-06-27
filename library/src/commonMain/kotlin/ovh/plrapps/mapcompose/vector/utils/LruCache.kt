package ovh.plrapps.mapcompose.vector.utils

class LruCache<K, V>(private val maxSize: Int) {
    private val cache = LinkedHashMap<K, V>(0, 0.75f)

    fun get(key: K): V? {
        val value = cache.remove(key)
        if (value != null) {
            cache[key] = value
        }
        return value
    }

    fun put(key: K, value: V) {
        cache.remove(key)
        if (cache.size >= maxSize) {
            val oldestKey = cache.keys.firstOrNull()
            if (oldestKey != null) {
                cache.remove(oldestKey)
            }
        }
        cache[key] = value
    }

    fun remove(key: K): V? { return cache.remove(key) }

    fun clear() { cache.clear() }

    fun size(): Int = cache.size
}
