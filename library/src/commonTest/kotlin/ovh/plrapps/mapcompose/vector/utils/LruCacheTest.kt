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
}
