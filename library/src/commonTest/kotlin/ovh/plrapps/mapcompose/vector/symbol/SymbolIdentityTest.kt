package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.renderer.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * That a label keeps its identity across the ways this port's bucket list can be transiently wrong.
 *
 * A lost [SymbolInstance.crossTileID] is what a flicker *is* here: `Placement.commit` keys every
 * opacity on it, so a symbol whose id changed is new by definition and restarts at opacity 0, while
 * its old id is still in the previous cycle's opacities and is faded out on top of it. The same
 * label is then drawn twice for a whole fade duration, one copy rising and one falling, which
 * composites to a visible dip.
 *
 * `CrossTileSymbolIndex` itself retires a tile the moment its bucket leaves the list it is handed,
 * which is upstream's behaviour and is pinned by `CrossTileSymbolIndexUpstreamTest`. What is this
 * port's own is *which* list it is handed -- see `VectorRasterizer.heldForSymbolFade`.
 */
class SymbolIdentityTest {

    /** The layer id the fixture rasterizer's style declares, which is what pruning keys on. */
    private val layerId = "labels"

    /** [mercatorX] keeps two symbols far enough apart on screen not to collide; see the fixture. */
    private fun instance(key: String, mercatorX: Double, x: Float, y: Float) =
        SymbolFixtures.textInstance(
            key = key,
            global = Point(mercatorX, 0.5),
            tileAnchor = Offset(x, y),
        )

    private fun bucket(instances: List<SymbolInstance>, x: Int, y: Int) =
        SymbolFixtures.bucket(instances = instances, z = 6, x = x, y = y, layerId = layerId)

    private fun viewportAt(centroidX: Double): ViewportInfo =
        SymbolFixtures.viewport(centroid = Point(centroidX, 0.5))

    private fun ids(bucket: SymbolBucket): List<Long> = bucket.instances.map { it.crossTileID }

    @Test
    fun `a bucket missing for one cycle keeps its identities`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val here = bucket(listOf(instance("Here", 0.45, 100f, 100f)), x = 8, y = 8)
        val there = bucket(listOf(instance("There", 0.55, 100f, 100f)), x = 9, y = 8)

        rasterizer.place(listOf(here, there), viewportAt(0.50), now = 0L)
        val before = ids(here)
        assertTrue(before.all { it != 0L }, "identities were assigned at all")

        /* One cycle where `here` is not in the list -- a layout run still in flight, or a tile whose
         * fetch has not landed. It is still on screen, so it must not be retired. */
        rasterizer.place(listOf(there), viewportAt(0.51), now = SYMBOL_FADE_DURATION_MS + 100)
        rasterizer.place(listOf(here, there), viewportAt(0.52), now = SYMBOL_FADE_DURATION_MS * 2 + 200)

        assertEquals(before, ids(here), "the same label kept the id it had")
    }

    @Test
    fun `a bucket gone for good does release its identities`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val here = bucket(listOf(instance("Here", 0.45, 100f, 100f)), x = 8, y = 8)
        val there = bucket(listOf(instance("There", 0.55, 100f, 100f)), x = 9, y = 8)

        rasterizer.place(listOf(here, there), viewportAt(0.50), now = 0L)
        val before = ids(here)

        // Long enough gone that the absence is real rather than a list that was transiently short.
        var now = SYMBOL_FADE_DURATION_MS
        repeat(6) {
            rasterizer.place(listOf(there), viewportAt(0.50 + it * 0.001), now = now)
            now += SYMBOL_FADE_DURATION_MS
        }
        rasterizer.place(listOf(here, there), viewportAt(0.60), now = now)

        assertNotEquals(before, ids(here), "the hold is bounded, so identities are not leaked")
    }

    @Test
    fun `a rebuilt bucket for the same tile keeps its identities`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val first = bucket(listOf(instance("Here", 0.45, 100f, 100f)), x = 8, y = 8)

        rasterizer.place(listOf(first), viewportAt(0.50), now = 0L)
        val before = ids(first)

        /* What an eviction from the bucket cache produces: a new object, with a new
         * `bucketInstanceId` and instances whose ids are still 0. */
        val rebuilt = bucket(listOf(instance("Here", 0.45, 100f, 100f)), x = 8, y = 8)
        rasterizer.place(listOf(rebuilt), viewportAt(0.51), now = SYMBOL_FADE_DURATION_MS + 100)

        assertEquals(before, ids(rebuilt), "the tile's own index entry handed the ids back")
    }

    @Test
    fun `a newly arrived bucket fades in once and does not restart`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val here = bucket(listOf(instance("Here", 0.45, 100f, 100f)), x = 8, y = 8)
        val arriving = bucket(listOf(instance("Arriving", 0.55, 300f, 300f)), x = 9, y = 8)

        rasterizer.place(listOf(here), viewportAt(0.50), now = 0L)

        val appeared = rasterizer.place(
            listOf(here, arriving), viewportAt(0.51), now = SYMBOL_FADE_DURATION_MS + 100,
        ).result!!
        val newId = arriving.instances.single().crossTileID
        assertEquals(
            0f,
            appeared.symbols.single { it.crossTileID == newId }.opacity.text.opacity,
            "a label seen for the first time starts from nothing",
        )

        // The same tile again, rebuilt as an eviction would rebuild it.
        val rebuilt = bucket(listOf(instance("Arriving", 0.55, 300f, 300f)), x = 9, y = 8)
        val settled = rasterizer.place(
            listOf(here, rebuilt), viewportAt(0.52), now = SYMBOL_FADE_DURATION_MS * 2 + 200,
        ).result!!

        assertEquals(newId, rebuilt.instances.single().crossTileID)
        assertEquals(
            1f,
            settled.symbols.single { it.crossTileID == newId }.opacity.text.opacity,
            "the fade finished rather than starting over",
        )
    }

    @Test
    fun `an overzoomed source keeps its identities through a zoom step`() = runTest {
        /* Past a source's `maxzoom` the canonical tile stops changing and only `span` grows, so the
         * departing bucket and the one replacing it land on the *same* cross-tile index slot -- which
         * is why `VectorRasterizer.heldForSymbolFade` keys the hold on that slot and not on
         * `SymbolBucket.key`. `CrossTileOverzoomTest` covers the index in isolation; this is the same
         * step driven through `place`, where the hold is. */
        val rasterizer = SymbolFixtures.rasterizer()
        val atOneStep = SymbolFixtures.bucket(
            instances = listOf(instance("Here", 0.45, 100f, 100f)),
            z = 14, x = 8, y = 8, layerId = layerId, span = 1, bucketZoom = 14,
        )
        val atNextStep = SymbolFixtures.bucket(
            instances = listOf(instance("Here", 0.45, 200f, 200f)),
            z = 14, x = 8, y = 8, layerId = layerId, span = 2, bucketZoom = 15,
        )

        rasterizer.place(listOf(atOneStep), viewportAt(0.50), now = 0L)
        val before = ids(atOneStep)

        rasterizer.place(listOf(atNextStep), viewportAt(0.51), now = SYMBOL_FADE_DURATION_MS + 100)

        assertEquals(before, ids(atNextStep), "the label past maxzoom is the same label")
    }

    @Test
    fun `a tile that failed to load contributes no bucket at all`() = runTest {
        var failing = true
        val rasterizer = SymbolFixtures.rasterizer(
            onFetch = { if (failing) throw IllegalStateException("no network") },
        )

        val whileFailing = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow()
        assertEquals(
            0,
            whileFailing.buckets.size,
            "an instance-less placeholder would install an empty index entry for a live tile",
        )

        failing = false
        val arrived = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        assertEquals(9, arrived.size, "the visible tile plus the ring around it")
    }

    @Test
    fun `a pass whose tiles did not arrive reports itself incomplete`() = runTest {
        /* What `VectorLayer` retries on. Nothing else asks for those tiles again: the layout pass
         * only re-runs when the tile set or the integer zoom changes, so an unretried failure leaves
         * every label of that tile unplaced -- it fades out, and fades back in whenever the map next
         * moves. That dip is the flicker. */
        var failing = true
        val rasterizer = SymbolFixtures.rasterizer(
            onFetch = { if (failing) throw IllegalStateException("no network") },
        )

        val whileFailing = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow()
        assertEquals(false, whileFailing.isComplete)
        assertEquals(9, whileFailing.unresolved, "one unresolved request per tile of the 3x3 block")

        failing = false
        val arrived = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow()
        assertEquals(true, arrived.isComplete)
        assertEquals(0, arrived.unresolved)
    }
}
