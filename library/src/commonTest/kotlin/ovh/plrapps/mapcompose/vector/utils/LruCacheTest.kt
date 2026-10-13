package ovh.plrapps.mapcompose.vector.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruCacheTest {

    @Test
    fun `the oldest entry is the one evicted`() {
        val cache = LruCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")          // "a" is now the most recently used
        cache.put("c", 3)

        assertNull(cache.get("b"), "the least recently used entry went")
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `growing the cache keeps what a smaller one would have evicted`() {
        /* Why `growTo` exists: a cache sized under its caller's live working set does not merely miss
         * more often, it evicts precisely what the next pass asks for. The symbol bucket cache's
         * working set is the viewport's, so it cannot be sized at construction. */
        val cache = LruCache<String, Int>(maxSize = 2)
        cache.growTo(4)
        for (i in 1..4) cache.put("k$i", i)

        assertEquals(4, cache.size())
        for (i in 1..4) assertEquals(i, cache.get("k$i"))
    }

    @Test
    fun `growing never lowers the size`() {
        val cache = LruCache<String, Int>(maxSize = 4)
        cache.growTo(2)
        assertEquals(4, cache.maxSize)
    }

    @Test
    fun `shrinking evicts the oldest entries beyond the new size`() {
        val cache = LruCache<String, Int>(maxSize = 4)
        for (i in 1..4) cache.put("k$i", i)
        cache.get("k1")         // "k1" is now the most recently used

        cache.shrinkTo(2)

        assertEquals(2, cache.size())
        assertEquals(2, cache.maxSize)
        assertEquals(1, cache.get("k1"))
        assertEquals(4, cache.get("k4"))
        assertNull(cache.get("k2"))
    }

    @Test
    fun `shrinking never raises the size`() {
        val cache = LruCache<String, Int>(maxSize = 2)
        cache.shrinkTo(4)
        assertEquals(2, cache.maxSize)
    }

    @Test
    fun `a weighed cache evicts by weight before it reaches its entry count`() {
        val cache = LruCache<String, ByteArray>(maxSize = 100, maxWeight = 10, weigher = { it.size.toLong() })
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        assertEquals(8, cache.weight)

        cache.put("c", ByteArray(4))

        assertNull(cache.get("a"), "the oldest entry went to make room")
        assertEquals(2, cache.size())
        assertEquals(8, cache.weight)
    }

    @Test
    fun `an entry heavier than the whole budget is kept alone`() {
        val cache = LruCache<String, ByteArray>(maxSize = 100, maxWeight = 10, weigher = { it.size.toLong() })
        cache.put("a", ByteArray(4))
        cache.put("big", ByteArray(50))

        assertEquals(1, cache.size())
        assertEquals(50, cache.weight)
        assertEquals(50, cache.get("big")?.size)
    }

    @Test
    fun `replacing and removing an entry keep the weight exact`() {
        val cache = LruCache<String, ByteArray>(maxSize = 100, maxWeight = 100, weigher = { it.size.toLong() })
        cache.put("a", ByteArray(4))
        cache.put("a", ByteArray(6))
        assertEquals(6, cache.weight)

        cache.remove("a")
        assertEquals(0, cache.weight)

        cache.put("b", ByteArray(3))
        cache.clear()
        assertEquals(0, cache.weight)
    }
}
