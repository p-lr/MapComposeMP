package ovh.plrapps.mapcompose.vector.symbol

import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.renderer.Point
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a new placement may start, ported from `Style._updatePlacement`
 * (`maplibre-gl-js/src/style/style.ts`) and asserted through [Placement.stillRecent].
 *
 * This is the half of upstream that keeps labels steady. A placement is *held*: the decisions stand
 * for a fade duration while the draw pass keeps re-projecting and re-scaling them, and only then is
 * a new one taken. Recomputing per viewport update instead -- which this port did -- makes every
 * near-threshold label re-decide sixty times a second, which is what a pinch looked like.
 *
 * Upstream's own `placement.test.ts` is not transcribed (it needs a `Style`, a `Transform` and a GL
 * bucket), so the rule is asserted directly.
 */
class PlacementSchedulingTest {

    private val fade = SYMBOL_FADE_DURATION_MS

    private fun placement(zoom: Double = 6.0, now: Long = 0L): Placement {
        val bucket = SymbolFixtures.bucket(
            listOf(SymbolFixtures.textInstance("label", global = Point(0.5, 0.5)))
        )
        CrossTileSymbolIndex().addLayer(SymbolFixtures.LAYER, listOf(bucket), density = 1f)
        val placement = Placement(
            viewportInfo = SymbolFixtures.viewport(fractionalZoom = zoom),
            zoom = zoom,
            collisionDetectionEnabled = true,
        )
        placement.placeBuckets(PlacementOrder(listOf(bucket)), previous = null)
        placement.commit(previous = null, now = now)
        return placement
    }

    @Test
    fun `a placement stays recent for one fade duration after it commits`() {
        val placement = placement(now = 1_000L)

        assertTrue(placement.stillRecent(now = 1_000L, newZoom = 6.0), "just committed")
        assertTrue(placement.stillRecent(now = 1_000L + fade - 1, newZoom = 6.0))
        assertFalse(placement.stillRecent(now = 1_000L + fade, newZoom = 6.0), "the window has passed")
    }

    @Test
    fun `a zoom that is still changing gets the full window`() {
        /* Upstream applies the shortening "only after the map has stopped zooming. This avoids
         * adding extra jank while zooming" -- the check compares against the zoom at the *previous*
         * call, so a moving zoom never matches. */
        val placement = placement(zoom = 6.0, now = 0L)

        // Each call reports a different zoom, so none of them ever matches the last one.
        assertTrue(placement.stillRecent(now = fade / 2, newZoom = 5.5))
        assertTrue(placement.stillRecent(now = fade / 2, newZoom = 5.0))
        assertTrue(placement.stillRecent(now = fade / 2, newZoom = 4.5))
    }

    @Test
    fun `a zoom that has stopped shortens the window in proportion to how far out it went`() {
        val placement = placement(zoom = 6.0, now = 0L)

        // First call at this zoom only records it; the second sees it unchanged and shortens.
        val zoomedOut = 5.25   // zoomAdjustment = (6.0 - 5.25) / 1.5 = 0.5
        assertTrue(placement.stillRecent(now = fade / 4, newZoom = zoomedOut))
        assertFalse(
            placement.stillRecent(now = fade * 3 / 4, newZoom = zoomedOut),
            "half the window is gone once the map has settled a zoom level and a half out",
        )
    }

    @Test
    fun `zooming in does not shorten the window`() {
        val placement = placement(zoom = 6.0, now = 0L)

        val zoomedIn = 7.0     // zoomAdjustment = max(0, (6.0 - 7.0) / 1.5) = 0
        assertTrue(placement.stillRecent(now = fade / 2, newZoom = zoomedIn))
        assertTrue(placement.stillRecent(now = fade * 3 / 4, newZoom = zoomedIn))
        assertFalse(placement.stillRecent(now = fade, newZoom = zoomedIn))
    }

    @Test
    fun `a placement is clean until something asks it to be stale`() {
        val placement = placement()

        assertFalse(placement.isStale)
        placement.setStale()
        assertTrue(placement.isStale, "inputs changed while it could not be replaced")
    }

    @Test
    fun `the remaining window says when a deferred placement can be retried`() {
        val placement = placement(now = 1_000L)

        assertEquals(fade, placement.recencyRemainingMs(now = 1_000L))
        assertEquals(fade / 2, placement.recencyRemainingMs(now = 1_000L + fade / 2))
        assertEquals(0L, placement.recencyRemainingMs(now = 1_000L + fade))
        assertEquals(0L, placement.recencyRemainingMs(now = 1_000L + fade * 10), "never negative")
    }

    // region VectorRasterizer.place -- upstream's Style._updatePlacement

    @Test
    fun `the first cycle always places`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val buckets = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets

        val outcome = rasterizer.place(buckets, SymbolFixtures.viewport(), now = 0L)

        assertNotNull(outcome.result)
        assertEquals(0L, outcome.retryInMs, "nothing pending")
    }

    @Test
    fun `a viewport that moved within the window defers rather than replacing`() = runTest {
        /* The flicker fix. Upstream will not start a new placement while the last is `stillRecent`,
         * so the decisions stand and only the draw pass follows the map. */
        val rasterizer = SymbolFixtures.rasterizer()
        val buckets = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        rasterizer.place(buckets, SymbolFixtures.viewport(), now = 0L)

        val moved = SymbolFixtures.viewport(centroid = Point(0.51, 0.5))
        val outcome = rasterizer.place(buckets, moved, now = fade / 2)

        assertNull(outcome.result, "held, not replaced")
        assertTrue(outcome.retryInMs > 0L, "and asked to be reconsidered once the window lapses")
    }

    @Test
    fun `a viewport that did not move asks for nothing at all`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val buckets = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        val viewportInfo = SymbolFixtures.viewport()
        rasterizer.place(buckets, viewportInfo, now = 0L)

        val outcome = rasterizer.place(buckets, viewportInfo, now = fade / 2)

        assertNull(outcome.result)
        assertEquals(0L, outcome.retryInMs, "clean, so the map can go idle")
    }

    @Test
    fun `the deferred change is placed once the window lapses`() = runTest {
        val rasterizer = SymbolFixtures.rasterizer()
        val buckets = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        rasterizer.place(buckets, SymbolFixtures.viewport(), now = 0L)

        val moved = SymbolFixtures.viewport(centroid = Point(0.51, 0.5))
        rasterizer.place(buckets, moved, now = fade / 2)
        val outcome = rasterizer.place(buckets, moved, now = fade + 1)

        assertNotNull(outcome.result, "the stale placement is retaken even though nothing moved since")
        assertEquals(0L, outcome.retryInMs)
    }

    @Test
    fun `a held placement keeps the symbols it committed`() = runTest {
        /* What the draw pass goes on drawing while a placement is held -- it re-projects and
         * re-scales these every frame rather than being handed new ones. */
        val rasterizer = SymbolFixtures.rasterizer()
        val buckets = rasterizer.layoutBuckets(SymbolFixtures.mvtViewport(6f), z = 6.0).getOrThrow().buckets
        val first = rasterizer.place(buckets, SymbolFixtures.viewport(), now = 0L).result
        assertNotNull(first)

        val moved = SymbolFixtures.viewport(centroid = Point(0.51, 0.5))
        assertNull(rasterizer.place(buckets, moved, now = fade / 2).result)

        val second = rasterizer.place(buckets, moved, now = fade + 1).result
        assertNotNull(second)
        assertFalse(second === first, "and it is a new one once the window lapses")
    }

    // endregion
}
