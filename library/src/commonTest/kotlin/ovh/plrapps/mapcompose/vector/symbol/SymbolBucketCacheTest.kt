package ovh.plrapps.mapcompose.vector.symbol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a bucket outlives the viewport, which is the whole point of splitting layout from placement.
 *
 * The observable is the tile fetch: `layoutBuckets` only fetches for the buckets it has to build, so
 * a second call over the same tiles at the same zoom asking for no tiles at all is the same
 * statement as "it built no buckets".
 */
class SymbolBucketCacheTest {

    /**
     * Counts fetches. The layout pass fetches concurrently on `Dispatchers.Default`, so the counter
     * is guarded -- an unguarded `++` here loses increments and the test reports one build too few.
     */
    private class FetchCounter {
        private val mutex = Mutex()
        private var count = 0

        suspend fun fetches(): Int = mutex.withLock { count }
        suspend fun record() { mutex.withLock { count++ } }
    }

    @Test
    fun `the same tiles at the same zoom are laid out once`() = runTest {
        val source = FetchCounter()
        val rasterizer = SymbolFixtures.rasterizer(onFetch = source::record)

        val first = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        val afterFirst = source.fetches()
        assertEquals(9, first.size, "the visible tile plus the ring around it")
        assertEquals(9, afterFirst, "one fetch per bucket, the first time round")

        val second = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets

        assertEquals(afterFirst, source.fetches(), "a second pass over the same tiles builds nothing")
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            kotlin.test.assertSame(first[i], second[i], "the cached bucket itself comes back")
        }
    }

    @Test
    fun `a working set larger than the cache's floor is still all cached`() = runTest {
        /* The cache used to be a flat 512, a number taken from the nine tiles of the viewport in the
         * test above. A real one is `(rows + 2) * (cols + 2) * symbolLayers`, which a phone already
         * exceeds by a wide margin with a real style -- and a plain LRU sized under its caller's live
         * set evicts exactly what the next pass asks for, so the layout pass rebuilt most of the map
         * every time it ran. A rebuilt bucket is one the cross-tile index has to re-derive
         * identities for, which is where a flicker comes from. */
        val source = FetchCounter()
        val rasterizer = SymbolFixtures.rasterizer(onFetch = source::record)
        val wide = MVTViewport(
            width = 512f,
            height = 512f,
            bearing = 0f,
            pitch = 0f,
            zoom = 6f,
            tileMatrix = (0..22).associateWith { 0..22 },
        )

        val first = rasterizer.layoutBuckets(wide, z = 6.0).getOrThrow().buckets
        val afterFirst = source.fetches()
        kotlin.test.assertTrue(first.size > 512, "the working set really is past the cache's floor")

        val second = rasterizer.layoutBuckets(wide, z = 6.0).getOrThrow().buckets

        assertEquals(afterFirst, source.fetches(), "a second pass over the same tiles builds nothing")
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            kotlin.test.assertSame(first[i], second[i], "the cached bucket itself comes back")
        }
    }

    @Test
    fun `a different zoom is a different bucket`() = runTest {
        /* A bucket's layout properties are evaluated at its own integer zoom, so the same tile at
         * another zoom is a different bucket -- upstream's `overscaledZ` in the tile's key. */
        val source = FetchCounter()
        val rasterizer = SymbolFixtures.rasterizer(onFetch = source::record)

        rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        val afterFirst = source.fetches()

        rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(7f), z = 7.0).getOrThrow().buckets

        kotlin.test.assertTrue(source.fetches() > afterFirst, "the new zoom's buckets are built")
    }
}
