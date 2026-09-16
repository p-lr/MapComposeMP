package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The port of `src/util/find_pole_of_inaccessibility.ts` -- where a polygon's label goes.
 *
 * Pure geometry, so it stays in `commonTest`. The assertions are on the property the algorithm
 * promises, not on exact coordinates: an approximation is allowed to land anywhere within its
 * precision.
 */
class FindPoleOfInaccessibilityTest {

    private fun ring(vararg points: Pair<Float, Float>) = points.toList()

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) =
        ring(x0 to y0, x1 to y0, x1 to y1, x0 to y1, x0 to y0)

    /** Shortest distance from a point to any edge of the polygon. */
    private fun distanceToEdges(
        point: Pair<Float, Float>,
        polygon: List<List<Pair<Float, Float>>>,
    ): Double {
        var best = Double.POSITIVE_INFINITY
        for (r in polygon) {
            var j = r.size - 1
            for (i in r.indices) {
                best = min(best, distanceToSegment(point, r[i], r[j]))
                j = i
            }
        }
        return best
    }

    private fun distanceToSegment(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
    ): Double {
        val dx = (b.first - a.first).toDouble()
        val dy = (b.second - a.second).toDouble()
        var cx = a.first.toDouble()
        var cy = a.second.toDouble()
        if (dx != 0.0 || dy != 0.0) {
            val t = ((p.first - a.first) * dx + (p.second - a.second) * dy) / (dx * dx + dy * dy)
            if (t > 1) {
                cx = b.first.toDouble()
                cy = b.second.toDouble()
            } else if (t > 0) {
                cx += dx * t
                cy += dy * t
            }
        }
        val ex = p.first - cx
        val ey = p.second - cy
        return sqrt(ex * ex + ey * ey)
    }

    @Test
    fun `a square is labelled at its centre`() {
        val polygon = listOf(square(0f, 0f, 100f, 100f))

        val pole = assertNotNull(findPoleOfInaccessibility(polygon, precision = 0.5))

        assertTrue(abs(pole.first - 50f) < 1f, "expected the centre, got $pole")
        assertTrue(abs(pole.second - 50f) < 1f, "expected the centre, got $pole")
    }

    @Test
    fun `a hole pushes the pole out of the middle`() {
        // A square with a square hole around its centre: the pole has to leave the middle.
        val polygon = listOf(
            square(0f, 0f, 100f, 100f),
            square(30f, 30f, 70f, 70f),
        )

        val pole = assertNotNull(findPoleOfInaccessibility(polygon, precision = 0.5))

        assertTrue(
            distanceToEdges(pole, polygon) > 10.0,
            "the pole should sit in the ring between the two squares, but was $pole",
        )
        val insideHole = pole.first > 30f && pole.first < 70f && pole.second > 30f && pole.second < 70f
        assertTrue(!insideHole, "the pole must not land in the hole, but was $pole")
    }

    @Test
    fun `an L shape is labelled inside one of its arms`() {
        /* The centroid of an L falls outside it, which is the case the search exists for: the
         * centroid is only preferred when it is itself inside the polygon. */
        val polygon = listOf(
            ring(
                0f to 0f, 100f to 0f, 100f to 30f, 30f to 30f, 30f to 100f, 0f to 100f, 0f to 0f,
            )
        )

        val pole = assertNotNull(findPoleOfInaccessibility(polygon, precision = 0.5))

        assertTrue(
            distanceToEdges(pole, polygon) > 12.0,
            "the pole should sit well inside an arm, but was $pole at ${distanceToEdges(pole, polygon)}",
        )
    }

    @Test
    fun `a degenerate polygon yields a point rather than nothing`() {
        // Every vertex on one line: upstream returns the bounding box's corner.
        val pole = assertNotNull(findPoleOfInaccessibility(listOf(ring(0f to 5f, 10f to 5f, 20f to 5f))))
        assertTrue(abs(pole.second - 5f) < 1e-3)
    }

    @Test
    fun `an empty polygon has no pole`() {
        assertNull(findPoleOfInaccessibility(emptyList()))
        assertNull(findPoleOfInaccessibility(listOf(emptyList())))
    }
}
