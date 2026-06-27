package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize

@OptIn(ExperimentalTestApi::class)
class CollisionDetectorTest {
    private fun label(
        id: String,
        x: Float, y: Float, w: Float, h: Float,
        overlapMode: OverlapMode = OverlapMode.Never,
        ignorePlacement: Boolean = false,
    ) = LabelPlacement(
        text = id,
        position = ObbPoint(x + w / 2, y + h / 2),
        angle = 0f,
        bounds = Rect(x, y, x + w, y + h),
        obb = OBB(
            center = ObbPoint(x + w / 2, y + h / 2),
            size = ObbSize(w, h),
            rotation = 0f
        ),
        layerIndex = 0,
        inLayerPriority = 0.0,
        overlapMode = overlapMode,
        ignorePlacement = ignorePlacement,
    )

    @Test
    fun testIntersectsTrue() {
        val r1 = Rect(0f, 0f, 10f, 10f)
        val r2 = Rect(5f, 5f, 15f, 15f)
        assertTrue(r1.intersects(r2))
    }

    @Test
    fun testIntersectsFalse() {
        val r1 = Rect(0f, 0f, 10f, 10f)
        val r2 = Rect(11f, 11f, 20f, 20f)
        assertFalse(r1.intersects(r2))
    }

    @Test
    fun testNeverBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // collides with p1
    }

    @Test
    fun testAlwaysIsPlacedAndBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Always)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))   // always placed
        assertFalse(detector.tryPlaceLabel(p2))  // p1 (Always) blocks p2 (Never)
    }

    @Test
    fun testAlwaysDoesNotBlockAlways() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Always)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Always)

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2)) // Always is never blocked
    }

    @Test
    fun testCooperativeDoesNotBlockCooperative() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Cooperative)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Cooperative)

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2)) // two Cooperative symbols can overlap
    }

    @Test
    fun testNeverBlocksCooperative() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Cooperative)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // Never blocks Cooperative
    }

    @Test
    fun testCooperativeBlocksNever() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Cooperative)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))
        assertFalse(detector.tryPlaceLabel(p2)) // Cooperative is in tree → blocks Never
    }

    @Test
    fun testIgnorePlacementDoesNotBlockOthers() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, ignorePlacement = true)
        val p2 = label("B", 5f, 5f, 10f, 10f, OverlapMode.Never)

        assertTrue(detector.tryPlaceLabel(p1))  // ignore-placement is always placed
        assertTrue(detector.tryPlaceLabel(p2))  // p1 not in tree → doesn't block p2
    }

    @Test
    fun testNonOverlappingNeverSymbolsAreAllPlaced() {
        val detector = CollisionDetector()
        val p1 = label("A", 0f, 0f, 10f, 10f, OverlapMode.Never)
        val p2 = label("B", 20f, 0f, 10f, 10f, OverlapMode.Never) // no overlap

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2))
    }
}
