package ovh.plrapps.mapcompose.vector.renderer.collision

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

    @Test
    fun `spacing is measured along the whole line not per segment`() {
        // A straight 300 px line, drawn once as one segment and once finely subdivided. The walk
        // used to restart its phase at every vertex, so the subdivided copy produced a label at
        // every vertex instead of every `spacing` pixels.
        val single = listOf(0f to 0f, 300f to 0f)
        val subdivided = (0..30).map { (it * 10f) to 0f }
        val placementsSingle = LineLabelPlacement.calculatePlacements(single, 20f, 100f)
        val placementsSubdivided = LineLabelPlacement.calculatePlacements(subdivided, 20f, 100f)
        assertEquals(placementsSingle.size, placementsSubdivided.size)
        for (i in placementsSingle.indices) {
            assertTrue(
                kotlin.math.abs(placementsSingle[i].first.first - placementsSubdivided[i].first.first) < 0.01f,
                "expected ${placementsSingle[i].first} but was ${placementsSubdivided[i].first}",
            )
        }
    }

    @Test
    fun `labels are one spacing apart`() {
        val points = listOf(0f to 0f, 500f to 0f)
        val placements = LineLabelPlacement.calculatePlacements(points, 20f, 100f)
        assertTrue(placements.size >= 3)
        for (i in 1 until placements.size) {
            val gap = placements[i].first.first - placements[i - 1].first.first
            assertTrue(kotlin.math.abs(gap - 100f) < 0.01f, "expected a 100 px gap but was $gap")
        }
    }

    @Test
    fun `a zero spacing yields nothing rather than looping`() {
        assertEquals(emptyList(), LineLabelPlacement.calculatePlacements(listOf(0f to 0f, 100f to 0f), 20f, 0f))
    }

    @Test
    fun `line-center places one label at the middle of the length`() {
        val points = listOf(0f to 0f, 100f to 0f)
        val (pos, angle) = LineLabelPlacement.centerPlacement(points)!!
        assertEquals(50f, pos.first)
        assertEquals(0f, pos.second)
        assertEquals(0f, angle)
    }

    @Test
    fun `line-center uses the length not the middle vertex`() {
        // Three vertices, but the middle one is nowhere near the middle of the line.
        val points = listOf(0f to 0f, 10f to 0f, 100f to 0f)
        val (pos, _) = LineLabelPlacement.centerPlacement(points)!!
        assertEquals(50f, pos.first)
    }

    @Test
    fun `line-center follows the segment it lands on`() {
        val points = listOf(0f to 0f, 0f to 100f)
        val (pos, angle) = LineLabelPlacement.centerPlacement(points)!!
        assertEquals(50f, pos.second)
        assertEquals(90f, angle)
    }

    @Test
    fun `line-center needs a line`() {
        assertEquals(null, LineLabelPlacement.centerPlacement(listOf(0f to 0f)))
        assertEquals(null, LineLabelPlacement.centerPlacement(listOf(0f to 0f, 0f to 0f)))
    }
}
