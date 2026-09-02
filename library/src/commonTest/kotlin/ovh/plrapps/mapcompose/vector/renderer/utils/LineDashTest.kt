package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineDashTest {

    private fun assertNear(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) < 1e-3f, "${if (message.isEmpty()) "" else "$message: "}expected $expected but was $actual")
    }

    private val straight = floatArrayOf(0f, 0f, 100f, 0f)

    @Test
    fun `an even dasharray alternates painted and blank runs`() {
        val runs = dashRuns(straight, DashPattern(floatArrayOf(10f, 10f), phase = 0f))

        assertEquals(5, runs.size, "five ten-pixel dashes fit into a hundred pixels")
        assertNear(0f, runs[0].points[0])
        assertNear(10f, runs[0].points[2])
        assertNear(20f, runs[1].points[0])
        assertNear(30f, runs[1].points[2])
    }

    @Test
    fun `a run knows how far along the line it starts`() {
        val runs = dashRuns(straight, DashPattern(floatArrayOf(10f, 10f), phase = 0f))

        assertNear(0f, runs[0].startDistance)
        assertNear(20f, runs[1].startDistance)
        assertNear(40f, runs[2].startDistance)
    }

    @Test
    fun `the phase starts the line part way into its first dash`() {
        // The odd-array form: a dash of 4 entered 3 pixels in, so the first run is one pixel long.
        val runs = dashRuns(straight, DashPattern(floatArrayOf(4f, 2f), phase = 3f))

        assertNear(0f, runs[0].points[0])
        assertNear(1f, runs[0].points[2], "the first dash is what is left of it")
        assertNear(3f, runs[1].points[0], "and the next starts after the gap")
    }

    @Test
    fun `a dash carries on across a corner`() {
        val corner = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        val runs = dashRuns(corner, DashPattern(floatArrayOf(15f, 5f), phase = 0f))

        assertEquals(3, runs.first().points.size / 2, "the first dash keeps the corner vertex")
        assertNear(10f, runs.first().points[2])
        assertNear(0f, runs.first().points[3])
        assertNear(10f, runs.first().points[4])
        assertNear(5f, runs.first().points[5])
    }

    @Test
    fun `a pattern longer than the line leaves one run`() {
        val runs = dashRuns(straight, DashPattern(floatArrayOf(1000f, 1000f), phase = 0f))

        assertEquals(1, runs.size)
        assertNear(100f, runs.single().points[2])
    }

    @Test
    fun `a zero-length dash is dropped rather than drawn`() {
        val runs = dashRuns(straight, DashPattern(floatArrayOf(0f, 10f), phase = 0f))

        assertTrue(runs.isEmpty(), "nothing is painted: $runs")
    }

    @Test
    fun `the painted length is what the pattern asks for`() {
        val runs = dashRuns(straight, DashPattern(floatArrayOf(6f, 4f), phase = 0f))

        val painted = runs.sumOf { run ->
            var length = 0.0
            for (i in 1 until run.points.size / 2) {
                length += kotlin.math.hypot(
                    run.points[2 * i] - run.points[2 * i - 2],
                    run.points[2 * i + 1] - run.points[2 * i - 1],
                ).toDouble()
            }
            length
        }
        assertTrue(abs(painted - 60.0) < 1e-3, "sixty of a hundred pixels are painted: $painted")
    }

    @Test
    fun `dasharray lengths scale with the line width`() {
        val pattern = dashPattern(listOf(1.0, 2.0), lineWidthPx = 6f)!!

        assertEquals(listOf(6f, 12f), pattern.intervals.toList())
        assertEquals(0f, pattern.phase)
    }

    @Test
    fun `an odd dasharray joins its last dash to its first`() {
        val pattern = dashPattern(listOf(1.0, 2.0, 3.0), lineWidthPx = 1f)!!

        assertEquals(listOf(4f, 2f), pattern.intervals.toList())
        assertEquals(3f, pattern.phase)
    }

    @Test
    fun `a dasharray that cannot produce a dash is ignored`() {
        assertEquals(null, dashPattern(listOf(1.0), lineWidthPx = 1f))
        assertEquals(null, dashPattern(listOf(0.0, 0.0), lineWidthPx = 1f))
        assertEquals(null, dashPattern(listOf(1.0, 1.0), lineWidthPx = 0f))
        assertEquals(null, dashPattern(listOf(-1.0, 2.0), lineWidthPx = 1f))
    }
}
