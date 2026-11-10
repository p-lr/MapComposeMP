package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The label path along a line, and the circle chain that follows it.
 *
 * Upstream's `projection.test.ts` drives `placeFirstAndLastGlyph` through a GL glyph-offset buffer
 * and a `mat4`, neither of which exists here, so these are this port's own tests of the same two
 * jobs: walk the line for the label's own length, and space circles along the result.
 */
class SymbolProjectionTest {

    private fun assertEquals(expected: List<Float>, actual: List<Float>, tolerance: Float) {
        assertEquals(expected.size, actual.size, "sizes")
        for (i in expected.indices) assertEquals(expected[i], actual[i], tolerance, "at $i")
    }

    private fun straightLine(): List<Pair<Float, Float>> =
        listOf(0f to 100f, 100f to 100f, 200f to 100f, 300f to 100f)

    @Test
    fun `the label path covers half its width either side of the anchor`() {
        val path = SymbolProjection.labelPath(straightLine(), anchor = Offset(150f, 100f), halfLength = 40f)

        assertNotNull(path)
        assertEquals(110f, path.first().x, 1e-3f)
        assertEquals(190f, path.last().x, 1e-3f)
        assertTrue(path.all { abs(it.y - 100f) < 1e-3f })
    }

    @Test
    fun `the path keeps the line's own vertices so it bends where the road does`() {
        val corner = listOf(0f to 0f, 100f to 0f, 100f to 100f)

        val path = SymbolProjection.labelPath(corner, anchor = Offset(100f, 0f), halfLength = 50f)

        assertNotNull(path)
        // 50 back along the first leg, the corner itself, and 50 down the second.
        assertEquals(3, path.size)
        assertEquals(Offset(50f, 0f), path[0])
        assertEquals(Offset(100f, 0f), path[1])
        assertEquals(Offset(100f, 50f), path[2])
    }

    @Test
    fun `the path stops at the end of a line shorter than the label`() {
        val path = SymbolProjection.labelPath(straightLine(), anchor = Offset(20f, 100f), halfLength = 100f)

        assertNotNull(path)
        assertEquals(0f, path.first().x, 1e-3f, "the walk cannot go past the line's start")
        assertEquals(120f, path.last().x, 1e-3f)
    }

    @Test
    fun `the label path reports where the anchor sits in it`() {
        val corner = listOf(0f to 0f, 100f to 0f, 100f to 100f)

        val path = SymbolProjection.labelPathOf(corner, anchor = Offset(100f, 0f), halfLength = 50f)

        assertNotNull(path)
        assertEquals(1, path.anchorIndex)
        assertEquals(Offset(100f, 0f), path.points[path.anchorIndex])
    }

    @Test
    fun `glyphs on a straight path are evenly spaced and level`() {
        val path = listOf(Offset(0f, 100f), Offset(200f, 100f))

        val placed = SymbolProjection.placeGlyphsAlongPath(
            path = path,
            anchorDistance = 100f,
            offsets = floatArrayOf(-20f, 0f, 20f),
        )

        assertNotNull(placed)
        assertEquals(listOf(80f, 100f, 120f), placed.map { it.x }, tolerance = 1e-3f)
        assertTrue(placed.all { abs(it.y - 100f) < 1e-3f })
        assertTrue(placed.all { abs(it.angleDeg) < 1e-3f })
    }

    @Test
    fun `glyphs turn with the path at a corner`() {
        val path = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f))

        val placed = SymbolProjection.placeGlyphsAlongPath(
            path = path,
            anchorDistance = 100f,
            offsets = floatArrayOf(-50f, 50f),
        )

        assertNotNull(placed)
        assertEquals(Offset(50f, 0f), Offset(placed[0].x, placed[0].y))
        assertEquals(0f, placed[0].angleDeg, 1e-3f)
        assertEquals(Offset(100f, 50f), Offset(placed[1].x, placed[1].y))
        assertEquals(90f, placed[1].angleDeg, 1e-3f, "the canvas turns clockwise for a positive angle")
    }

    @Test
    fun `a glyph past the end of the path yields nothing`() {
        val path = listOf(Offset(0f, 0f), Offset(100f, 0f))

        // Upstream's `notEnoughRoom`: the label runs off the line, so it is not placed along it.
        assertEquals(
            null,
            SymbolProjection.placeGlyphsAlongPath(path, anchorDistance = 90f, offsets = floatArrayOf(0f, 30f)),
        )
    }

    @Test
    fun `flipping reverses the traversal and turns every glyph around`() {
        val path = listOf(Offset(0f, 0f), Offset(200f, 0f))
        val offsets = floatArrayOf(-40f, 40f)

        val forward = SymbolProjection.placeGlyphsAlongPath(path, 100f, offsets)
        val flipped = SymbolProjection.placeGlyphsAlongPath(path, 100f, offsets, flip = true)

        assertNotNull(forward)
        assertNotNull(flipped)
        assertEquals(listOf(60f, 140f), forward.map { it.x }, tolerance = 1e-3f)
        assertEquals(listOf(140f, 60f), flipped.map { it.x }, tolerance = 1e-3f)
        assertTrue(flipped.all { abs(abs(it.angleDeg) - 180f) < 1e-3f })
    }

    @Test
    fun `a perpendicular offset moves every glyph along the normal`() {
        val down = SymbolProjection.placeGlyphsAlongPath(
            path = listOf(Offset(0f, 0f), Offset(100f, 0f)),
            anchorDistance = 50f,
            offsets = floatArrayOf(0f),
            perpendicular = 10f,
        )
        // On a leg running downwards the same offset moves the glyph the other way in x.
        val across = SymbolProjection.placeGlyphsAlongPath(
            path = listOf(Offset(0f, 0f), Offset(0f, 100f)),
            anchorDistance = 50f,
            offsets = floatArrayOf(0f),
            perpendicular = 10f,
        )

        assertNotNull(down)
        assertNotNull(across)
        assertEquals(Offset(50f, 10f), Offset(down[0].x, down[0].y))
        assertEquals(Offset(-10f, 50f), Offset(across[0].x, across[0].y))
    }

    @Test
    fun `atDistance agrees with lerp when there is no padding`() {
        val interpolator = PathInterpolator(listOf(0f to 0f, 100f to 0f, 100f to 100f))

        val mid = interpolator.atDistance(interpolator.length / 2f)
        val lerped = interpolator.lerp(0.5f)

        assertNotNull(mid)
        assertEquals(lerped.first, mid.x, 1e-3f)
        assertEquals(lerped.second, mid.y, 1e-3f)
        assertEquals(null, interpolator.atDistance(-1f))
        assertEquals(null, interpolator.atDistance(interpolator.length + 1f))
    }

    @Test
    fun `a line with fewer than two points has no path`() {
        assertEquals(null, SymbolProjection.labelPath(listOf(0f to 0f), Offset.Zero, 10f))
        assertEquals(null, SymbolProjection.labelPath(emptyList(), Offset.Zero, 10f))
    }

    @Test
    fun `the circles cover the path at upstream's spacing`() {
        val path = listOf(Offset(0f, 0f), Offset(200f, 0f))
        val radius = 10f

        val circles = SymbolProjection.collisionCircles(path, radius)

        assertTrue(circles.size > 1)
        assertTrue(circles.all { it.radius == radius })
        // Every circle sits on the path, and the chain spans it end to end bar the padding.
        assertTrue(circles.all { abs(it.y) < 1e-3f })
        assertEquals(radius * SymbolProjection.END_PADDING_FACTOR, circles.first().x, 1e-3f)
        assertEquals(200f - radius * SymbolProjection.END_PADDING_FACTOR, circles.last().x, 1e-3f)
        // Adjacent circles are no further apart than upstream's tolerated distance.
        for ((a, b) in circles.zipWithNext()) {
            val d = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            assertTrue(d <= radius * SymbolProjection.CIRCLE_DISTANCE_FACTOR + 1e-3f, "gap $d")
        }
    }

    @Test
    fun `a path shorter than half a radius gets a single circle`() {
        val circles = SymbolProjection.collisionCircles(listOf(Offset(0f, 0f), Offset(2f, 0f)), radius = 10f)

        assertEquals(1, circles.size)
    }

    @Test
    fun `a path entirely outside the padded viewport gets no circles`() {
        val bounds = SymbolProjection.ClipBounds(0f, 0f, 100f, 100f)

        val circles = SymbolProjection.collisionCircles(
            listOf(Offset(200f, 200f), Offset(300f, 200f)), radius = 10f, clipBounds = bounds,
        )

        assertTrue(circles.isEmpty())
    }

    @Test
    fun `a path crossing the viewport edge is clipped to the visible part`() {
        val bounds = SymbolProjection.ClipBounds(0f, 0f, 100f, 100f)

        val circles = SymbolProjection.collisionCircles(
            listOf(Offset(-100f, 50f), Offset(300f, 50f)), radius = 5f, clipBounds = bounds,
        )

        assertTrue(circles.isNotEmpty())
        assertTrue(circles.all { it.x >= -1e-3f && it.x <= 100f + 1e-3f }, "clipped to the viewport")
    }

    @Test
    fun `a chain along a curve claims less ground than the label's straight envelope`() {
        /* The reason the chain exists at all, and the mirror of the pair of cases in
         * `CollisionDetectorTest`: a label following a bend has an envelope far larger than its
         * body, and a neighbour sitting in the bend's shoulder is blocked by the envelope and not by
         * the label. */
        val arc = listOf(Offset(0f, 0f), Offset(40f, 40f), Offset(80f, 0f))
        val circles = SymbolProjection.collisionCircles(arc, radius = 6f)
        assertTrue(circles.isNotEmpty())

        val neighbour = SymbolFixtures.labelPlacement("neighbour", Offset(40f, 4f), width = 12f, height = 8f)
        val chained = SymbolFixtures.labelPlacement("arc", Offset(40f, 20f), width = 1f, height = 1f)
            .copy(circles = circles)

        val withChain = CollisionDetector()
        assertTrue(withChain.tryPlaceLabel(chained))
        assertTrue(withChain.tryPlaceLabel(neighbour), "the bend's shoulder is free")

        val asBox = CollisionDetector()
        assertTrue(asBox.tryPlaceLabel(SymbolFixtures.labelPlacement("arc", Offset(40f, 20f), width = 92f, height = 52f)))
        assertFalse(asBox.tryPlaceLabel(neighbour), "the straight envelope claims it")
    }
}
