package ovh.plrapps.mapcompose.maplibre.renderer.collision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineLabelPlacementTest {
    @Test
    fun testSingleSegmentOneLabel() {
        val points = listOf(0f to 0f, 100f to 0f)
        val textWidth = 40f
        val spacing = 100f
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth, spacing)
        assertEquals(1, placements.size)
        val (pos, angle) = placements[0]
        assertTrue(pos.first > 0 && pos.first < 100)
        assertEquals(0f, angle)
    }

    @Test
    fun testLongSegmentMultipleLabels() {
        val points = listOf(0f to 0f, 300f to 0f)
        val textWidth = 40f
        val spacing = 100f
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth, spacing)
        assertTrue(placements.size > 1)
        for (i in 1 until placements.size) {
            val prev = placements[i-1].first.first
            val curr = placements[i].first.first
            assertTrue(curr > prev)
        }
    }

    @Test
    fun testVerticalSegmentAngle() {
        val points = listOf(0f to 0f, 0f to 100f)
        val textWidth = 20f
        val spacing = 50f
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth, spacing)
        assertTrue(placements.isNotEmpty())
        val angle = placements[0].second
        assertTrue(angle == 90f || angle == -90f)
    }

    @Test
    fun testNoPlacementIfTooShort() {
        val points = listOf(0f to 0f, 10f to 0f)
        val textWidth = 20f
        val spacing = 100f
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth, spacing)
        assertTrue(placements.isEmpty())
    }
} 