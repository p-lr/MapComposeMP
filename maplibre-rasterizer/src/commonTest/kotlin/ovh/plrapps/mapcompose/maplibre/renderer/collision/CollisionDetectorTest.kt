package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ovh.plrapps.mapcompose.maplibre.utils.obb.OBB
import ovh.plrapps.mapcompose.maplibre.utils.obb.ObbPoint
import ovh.plrapps.mapcompose.maplibre.utils.obb.Size as ObbSize

@OptIn(ExperimentalTestApi::class)
class CollisionDetectorTest {
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
    fun testCollisionWithPriority() {
        val detector = CollisionDetector()
        val p1 = LabelPlacement(
            "A",
            ObbPoint(0f, 0f),
            0f,
            Rect(0f, 0f, 10f, 10f),
            obb = OBB(
                center = ObbPoint((0f + 10f) / 2, (0f + 10f) / 2),
                size = ObbSize(10f - 0f, 10f - 0f),
                rotation = 0f
            ),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false,
        )
        // TODO WIP
        val p2 = LabelPlacement(
            "B",
            ObbPoint(5f, 5f),
            0f,
            Rect(5f, 5f, 15f, 15f),
            obb = OBB(
                center = ObbPoint((5f + 15f) / 2, (5f + 15f) / 2),
                size = ObbSize(15f - 5f, 15f - 5f),
                rotation = 0f
            ),
            priority = 2,
            allowOverlap = false,
            ignorePlacement = false
        )

        assertTrue(detector.tryPlaceLabel(p1))
    }

    @Test
    fun testAllowOverlap() {
        val detector = CollisionDetector()
        val p1 = LabelPlacement(
            "A",
            ObbPoint(0f, 0f),
            0f,
            Rect(0f, 0f, 10f, 10f),
            obb = OBB(
                center = ObbPoint((0f + 10f) / 2, (0f + 10f) / 2),
                size = ObbSize(10f - 0f, 10f - 0f),
                rotation = 0f
            ),
            priority = 1,
            allowOverlap = true,
            ignorePlacement = false
        )
        val p2 = LabelPlacement(
            "B",
            ObbPoint(5f, 5f),
            0f,
            Rect(5f, 5f, 15f, 15f),
            obb = OBB(
                center = ObbPoint((5f + 15f) / 2, (5f + 15f) / 2),
                size = ObbSize(15f - 5f, 15f - 5f),
                rotation = 0f
            ),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false
        )

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2))
    }

    @Test
    fun testIgnorePlacement() {
        val detector = CollisionDetector()
        val p1 = LabelPlacement(
            "A",
            ObbPoint(0f, 0f),
            0f,
            Rect(0f, 0f, 10f, 10f),
            obb = OBB(
                center = ObbPoint((0f + 10f) / 2, (0f + 10f) / 2),
                size = ObbSize(10f - 0f, 10f - 0f),
                rotation = 0f
            ),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = true
        )
        val p2 = LabelPlacement(
            "B",
            ObbPoint(5f, 5f),
            0f,
            Rect(5f, 5f, 15f, 15f),
            obb = OBB(
                center = ObbPoint((5f + 15f) / 2, (5f + 15f) / 2),
                size = ObbSize(15f - 5f, 15f - 5f),
                rotation = 0f
            ),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false
        )

        assertTrue(detector.tryPlaceLabel(p1))
        assertTrue(detector.tryPlaceLabel(p2))
    }
} 