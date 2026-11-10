package ovh.plrapps.mapcompose.vector.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How many bytes of tile bitmap may be rasterizing at once, and how many tiles.
 *
 * MapCompose's `TileCollector` runs one worker per `workerCount` -- 16 in the demos -- and every
 * worker that picks up a vector tile rasterizes it. Nothing bounded that: on a phone each tile was a
 * multi-megabyte bitmap, and sixteen of them at once is more than the app has. Fetching is not
 * covered here, only the rasterization that follows it, so a slow network never holds the budget.
 *
 * A request larger than the whole budget is still served, alone, rather than never.
 */
internal class RenderBudget(
    private val budgetBytes: Long,
    private val maxConcurrent: Int,
) {
    private val mutex = Mutex()
    private var bytesInUse = 0L
    private var active = 0

    /** Bumped on every release; a waiter re-checks whenever it changes. */
    private val releases = MutableStateFlow(0L)

    suspend fun <T> withBudget(bytes: Long, block: suspend () -> T): T {
        acquire(bytes)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release(bytes) }
        }
    }

    private suspend fun acquire(bytes: Long) {
        while (true) {
            val seen = releases.value
            val granted = mutex.withLock {
                val fits = active == 0 || (active < maxConcurrent && bytesInUse + bytes <= budgetBytes)
                if (fits) {
                    bytesInUse += bytes
                    active += 1
                }
                fits
            }
            if (granted) return
            releases.first { it != seen }
        }
    }

    private suspend fun release(bytes: Long) {
        mutex.withLock {
            bytesInUse -= bytes
            active -= 1
        }
        releases.update { it + 1 }
    }

    internal suspend fun inUse(): Pair<Long, Int> = mutex.withLock { bytesInUse to active }
}

/**
 * The default for [RenderBudget]'s byte budget on this platform.
 *
 * Tile bitmaps live in native memory on every target, but a phone has far less of it to spare, and
 * on Android the JVM heap copies a tile still makes on its way to core count against the app's heap
 * limit.
 */
internal expect fun defaultRenderBudgetBytes(): Long

/**
 * The default for how many bytes `VectorRasterizer`'s tile caches may hold between them: encoded
 * bytes, decoded tiles, raster images and elevation data. See `VectorRasterizer.CacheBudget`.
 */
internal expect fun defaultCacheBudgetBytes(): Long

/**
 * How many tiles may rasterize at once on this platform.
 *
 * Bitmap bytes are not the whole cost of a render: each one also holds its decoded tiles, every
 * feature's evaluation view and its decoded geometry on the heap for as long as it runs. Where the
 * heap is small -- Android -- that, not the bitmaps, is what runs out first, so it bounds the count.
 */
internal expect fun defaultMaxConcurrentRenders(): Int

/**
 * What one decoded `Tile.Feature` costs beyond its integers: the object and the headers of its two
 * arrays. Calibrated on the JVM at 80; Kotlin/Native's object layout makes it about 135, measured in
 * a clean iOS simulator test heap over 76 swisstopo tiles (141 MB live against 107 MB estimated at 80).
 */
internal expect fun decodedFeatureBytes(): Long

/**
 * The fixed cost of one polygon's fill `Path` in a `TileGeometry`, on top of its coordinates.
 *
 * On iOS a materialized Compose `Path` is about 650 bytes of Kotlin heap -- the skiko wrapper, its
 * native peer's cleaner and the snapshot `materialize` forces -- plus about 900 bytes of native Skia
 * path, whatever its size. Measured in a clean test heap over 76 swisstopo tiles, their geometry
 * came to 638 MB against the 213 MB the JVM-calibrated formula gave, so the geometry cache held
 * three times its share; 1400 per polygon closes the gap (649 MB). The other platforms keep 0
 * because nobody has measured them there.
 */
internal expect fun fillPathBytes(): Long

internal const val MB = 1024L * 1024L
