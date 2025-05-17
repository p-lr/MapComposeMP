package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            0f to 0f,
            0f,
            Rect(0f, 0f, 10f, 10f),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false,
            textLayoutResult = null
        )
        val p2 = LabelPlacement(
            "B",
            5f to 5f,
            0f,
            Rect(5f, 5f, 15f, 15f),
            priority = 2,
            allowOverlap = false,
            ignorePlacement = false,
            textLayoutResult = null
        )
        assertFalse(detector.hasCollision(p1))
        detector.addPlacement(p1)
        // p2 overlaps with p1 but has higher priority, so p1 will be removed
        assertFalse(detector.hasCollision(p2))
        detector.addPlacement(p2)
        // p1 should no longer be in the list
        assertTrue(detector.hasCollision(p1))
    }

    @Test
    fun testAllowOverlap() {
        val detector = CollisionDetector()
        val p1 = LabelPlacement(
            "A",
            0f to 0f,
            0f,
            Rect(0f, 0f, 10f, 10f),
            priority = 1,
            allowOverlap = true,
            ignorePlacement = false,
            textLayoutResult = null
        )
        val p2 = LabelPlacement(
            "B",
            5f to 5f,
            0f,
            Rect(5f, 5f, 15f, 15f),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false,
            textLayoutResult = null
        )
        assertFalse(detector.hasCollision(p1))
        detector.addPlacement(p1)
        assertFalse(detector.hasCollision(p2))
    }

    @Test
    fun testIgnorePlacement() {
        val detector = CollisionDetector()
        val p1 = LabelPlacement(
            "A",
            0f to 0f,
            0f,
            Rect(0f, 0f, 10f, 10f),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = true,
            textLayoutResult = null
        )
        val p2 = LabelPlacement(
            "B",
            5f to 5f,
            0f,
            Rect(5f, 5f, 15f, 15f),
            priority = 1,
            allowOverlap = false,
            ignorePlacement = false,
            textLayoutResult = null
        )
        assertFalse(detector.hasCollision(p1))
        detector.addPlacement(p1)
        assertFalse(detector.hasCollision(p2))
    }
} 