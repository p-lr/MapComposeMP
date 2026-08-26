package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CheapRuler] is a port of `maplibre-style-spec/src/util/cheap_ruler.ts`, which has no upstream
 * test file — it is exercised only indirectly, through the `distance` conformance fixtures.
 *
 * The expected distances were produced by running that TypeScript in node against the same inputs,
 * so this checks the port against the reference implementation rather than against itself.
 */
class CheapRulerTest {

    private val ruler = CheapRuler(50.5)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    @Test
    fun `distance between two nearby points`() {
        assertClose(1319.3888214607498, ruler.distance(doubleArrayOf(30.5, 50.5), doubleArrayOf(30.51, 50.49)))
    }

    @Test
    fun `distance from a point to itself is zero`() {
        assertEquals(0.0, ruler.distance(doubleArrayOf(30.5, 50.5), doubleArrayOf(30.5, 50.5)))
    }

    @Test
    fun `one degree of longitude and one of latitude at this latitude`() {
        assertClose(70949.44217733943, ruler.distance(doubleArrayOf(0.0, 50.5), doubleArrayOf(1.0, 50.5)))
        assertClose(111238.68606147585, ruler.distance(doubleArrayOf(0.0, 50.5), doubleArrayOf(0.0, 51.5)))
    }

    /** A one-degree gap across the antimeridian must measure the same as one anywhere else. */
    @Test
    fun `longitude difference wraps across the antimeridian`() {
        assertClose(70949.44217733943, ruler.distance(doubleArrayOf(179.5, 50.5), doubleArrayOf(-179.5, 50.5)))
    }

    private val line = listOf(
        doubleArrayOf(30.0, 50.0),
        doubleArrayOf(30.0, 51.0),
        doubleArrayOf(31.0, 51.0),
    )

    @Test
    fun `pointOnLine projects onto the nearest segment`() {
        val result = ruler.pointOnLine(line, doubleArrayOf(30.1, 50.5))
        assertClose(30.0, result.point[0])
        assertClose(50.5, result.point[1])
        assertEquals(0, result.index)
        assertClose(0.5, result.t)
    }

    @Test
    fun `pointOnLine clamps before the start of the line`() {
        val result = ruler.pointOnLine(line, doubleArrayOf(29.0, 49.0))
        assertClose(30.0, result.point[0])
        assertClose(50.0, result.point[1])
        assertEquals(0, result.index)
        assertEquals(0.0, result.t)
    }

    @Test
    fun `pointOnLine clamps past the end of the line`() {
        val result = ruler.pointOnLine(line, doubleArrayOf(32.0, 51.0))
        assertClose(31.0, result.point[0])
        assertClose(51.0, result.point[1])
        assertEquals(1, result.index)
        assertEquals(1.0, result.t)
    }

    @Test
    fun `a point already on the line projects onto itself`() {
        val result = ruler.pointOnLine(line, doubleArrayOf(30.0, 50.25))
        assertClose(30.0, result.point[0])
        assertClose(50.25, result.point[1])
        assertEquals(0.0, ruler.distance(doubleArrayOf(30.0, 50.25), result.point))
    }
}
