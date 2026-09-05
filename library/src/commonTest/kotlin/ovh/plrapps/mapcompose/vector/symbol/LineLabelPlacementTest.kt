package ovh.plrapps.mapcompose.vector.symbol

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

    @Test
    fun `a long label pushes the spacing apart`() {
        // Upstream's getAnchors: "If the label is long relative to the spacing, adjust the spacing
        // so there is always a minimum space of spacing / 4 between label edges."
        val points = listOf(0f to 0f, 2000f to 0f)
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth = 180f, spacing = 200f)
        assertTrue(placements.size >= 2)
        val gap = placements[1].first.first - placements[0].first.first
        assertTrue(kotlin.math.abs(gap - 230f) < 0.01f, "expected a 230 px gap but was $gap")
    }

    @Test
    fun `an anchor outside the tile is dropped`() {
        // The line runs well past the tile, but only the part inside it may carry a label.
        val points = listOf(0f to 50f, 1000f to 50f)
        val placements = LineLabelPlacement.calculatePlacements(
            points = points,
            textWidth = 20f,
            spacing = 100f,
            tileExtent = 300f,
        )
        assertTrue(placements.isNotEmpty())
        assertTrue(placements.all { it.first.first < 300f }, "an anchor landed outside the tile")
    }

    @Test
    fun `a label that would run past the end of the line is dropped`() {
        // 250 px of line, a 200 px label and a 100 px spacing: only the middle can hold it.
        val points = listOf(0f to 0f, 250f to 0f)
        val placements = LineLabelPlacement.calculatePlacements(points, textWidth = 200f, spacing = 100f)
        for ((pos, _) in placements) {
            assertTrue(pos.first - 100f >= -0.01f, "label starts before the line at ${pos.first}")
            assertTrue(pos.first + 100f <= 250.01f, "label ends past the line at ${pos.first}")
        }
    }

    @Test
    fun `a short line that still fits its label falls back to the middle`() {
        // The first anchor lands too near the end for the label to fit, so the regular walk finds
        // nothing and upstream re-samples once at the middle of the line.
        val points = listOf(10f to 50f, 130f to 50f)
        val placements = LineLabelPlacement.calculatePlacements(
            points = points,
            textWidth = 100f,
            spacing = 400f,
            tileExtent = 300f,
            fontSize = 25f,
        )
        assertEquals(1, placements.size)
        assertTrue(
            kotlin.math.abs(placements[0].first.first - 70f) < 0.01f,
            "expected the middle of the line but was ${placements[0].first}",
        )
    }

    @Test
    fun `a line continued past the tile edge gets no middle fallback`() {
        // Its first vertex sits exactly on the tile boundary, so the neighbouring tile carries the
        // rest of this road and would place the same label a second time.
        val points = listOf(0f to 50f, 120f to 50f)
        val placements = LineLabelPlacement.calculatePlacements(
            points = points,
            textWidth = 100f,
            spacing = 400f,
            tileExtent = 300f,
            fontSize = 25f,
        )
        assertTrue(placements.isEmpty())
    }

    @Test
    fun `an overzoomed line's anchors contain the ones a zoom level up`() {
        /* Upstream's `getAnchors` multiplies the first-anchor offset by `bucket.overscaling` before
         * taking it modulo the spacing, and this is what that buys. Past a source's `maxzoom` the
         * same canonical tile is laid out again at every display zoom, over a line that is
         * `overscaling` times longer in layout units at the same spacing -- so the anchors of the
         * coarser level have to be a *subset* of the finer level's, or every line label is a new
         * symbol at every zoom step: `CrossTileSymbolIndex` matches on position, so it fades out and
         * back in somewhere else on the road.
         *
         * The line is scaled by the overscaling, exactly as `SymbolLayerLayout` scales geometry into
         * a bucket's layout space (`layoutTileSize(density, span)`), while the spacing stays put. */
        val line = listOf(0f to 0f, 4000f to 0f)
        val spacing = 250f
        val textWidth = 20f

        val atOneStep = LineLabelPlacement.calculatePlacements(
            points = line, textWidth = textWidth, spacing = spacing, overscaling = 1,
        ).map { it.first.first }

        val atNextStep = LineLabelPlacement.calculatePlacements(
            points = line.map { (x, y) -> x * 2f to y * 2f },
            textWidth = textWidth, spacing = spacing, overscaling = 2,
        ).map { it.first.first / 2f }

        assertTrue(atOneStep.isNotEmpty(), "the line is long enough for several anchors")
        assertTrue(
            atNextStep.size > atOneStep.size,
            "half the spacing over the same line means about twice as many anchors",
        )
        for (anchor in atOneStep) {
            assertTrue(
                atNextStep.any { kotlin.math.abs(it - anchor) < 0.01f },
                "the anchor at $anchor survives the zoom step; the next level has $atNextStep",
            )
        }
    }
}
