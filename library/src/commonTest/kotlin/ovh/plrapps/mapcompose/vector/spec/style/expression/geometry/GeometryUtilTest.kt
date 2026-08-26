package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GeometryUtil][getTileCoordinates] is a port of `maplibre-style-spec/src/util/geometry_util.ts`,
 * which has no upstream test file — its behaviour is asserted only through the `within` and
 * `distance` conformance fixtures.
 */
class GeometryUtilTest {

    private fun ring(vararg coords: Pair<Double, Double>): List<Coord> =
        coords.map { doubleArrayOf(it.first, it.second) }

    // region projection

    /**
     * The two projection helpers are asymmetric, exactly as upstream is:
     * [getTileCoordinates] returns *global* tile units, while [getLngLatFromTileCoord] expects
     * *tile-local* ones. That is why `Within` subtracts the tile shift and `Distance` adds it, and
     * why a round trip has to do the same.
     */
    @Test
    fun `tile coordinates round trip back to lng lat`() {
        val canonical = CanonicalTileId(z = 3, x = 3, y = 3)
        val shiftX = canonical.x.toDouble() * EXTENT
        val shiftY = canonical.y.toDouble() * EXTENT

        for (position in listOf(
            listOf(0.0, 0.0), listOf(2.0, 2.0), listOf(-45.0, 45.0), listOf(179.0, -60.0),
        )) {
            val global = getTileCoordinates(position, canonical)
            val local = doubleArrayOf(global[0] - shiftX, global[1] - shiftY)
            val back = getLngLatFromTileCoord(local, canonical)
            // The projection rounds to whole tile units, so a round trip is close, not exact.
            assertTrue(abs(back[0] - position[0]) < 0.01, "lng: ${back[0]} vs ${position[0]}")
            assertTrue(abs(back[1] - position[1]) < 0.01, "lat: ${back[1]} vs ${position[1]}")
        }
    }

    @Test
    fun `the origin projects to the middle of the world at every zoom`() {
        for (z in 0..5) {
            val canonical = CanonicalTileId(z = z, x = 0, y = 0)
            val coord = getTileCoordinates(listOf(0.0, 0.0), canonical)
            val worldSize = (1 shl z).toDouble() * EXTENT
            assertEquals(worldSize / 2, coord[0])
            assertEquals(worldSize / 2, coord[1])
        }
    }

    // endregion

    // region bounding boxes

    @Test
    fun `updateBBox grows to cover every coordinate`() {
        val bbox = newBBox()
        updateBBox(bbox, doubleArrayOf(5.0, 10.0))
        updateBBox(bbox, doubleArrayOf(-3.0, 40.0))
        assertContentEqualsDouble(doubleArrayOf(-3.0, 10.0, 5.0, 40.0), bbox)
    }

    @Test
    fun `boxWithinBox requires strict containment`() {
        val outer = doubleArrayOf(0.0, 0.0, 10.0, 10.0)
        assertTrue(boxWithinBox(doubleArrayOf(1.0, 1.0, 9.0, 9.0), outer))
        // Touching an edge is not "within".
        assertFalse(boxWithinBox(doubleArrayOf(0.0, 1.0, 9.0, 9.0), outer))
        assertFalse(boxWithinBox(doubleArrayOf(1.0, 1.0, 10.0, 9.0), outer))
        assertFalse(boxWithinBox(outer, outer))
        assertFalse(boxWithinBox(doubleArrayOf(-1.0, -1.0, 11.0, 11.0), outer))
    }

    private fun assertContentEqualsDouble(expected: DoubleArray, actual: DoubleArray) {
        assertTrue(
            expected.size == actual.size && expected.indices.all { expected[it] == actual[it] },
            "expected ${expected.toList()}, got ${actual.toList()}",
        )
    }

    // endregion

    // region point in polygon

    private val square = listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0))

    @Test
    fun `pointWithinPolygon detects inside and outside`() {
        assertTrue(pointWithinPolygon(doubleArrayOf(5.0, 5.0), square))
        assertFalse(pointWithinPolygon(doubleArrayOf(15.0, 5.0), square))
        assertFalse(pointWithinPolygon(doubleArrayOf(-1.0, 5.0), square))
    }

    /** A point exactly on an edge is controlled by the `trueIfOnBoundary` flag. */
    @Test
    fun `pointWithinPolygon honours trueIfOnBoundary`() {
        val onEdge = doubleArrayOf(0.0, 5.0)
        assertFalse(pointWithinPolygon(onEdge, square, trueIfOnBoundary = false))
        assertTrue(pointWithinPolygon(onEdge, square, trueIfOnBoundary = true))

        val onCorner = doubleArrayOf(0.0, 0.0)
        assertFalse(pointWithinPolygon(onCorner, square, trueIfOnBoundary = false))
        assertTrue(pointWithinPolygon(onCorner, square, trueIfOnBoundary = true))
    }

    @Test
    fun `a hole punches through the polygon`() {
        val withHole = square + listOf(ring(3.0 to 3.0, 3.0 to 7.0, 7.0 to 7.0, 7.0 to 3.0, 3.0 to 3.0))
        assertTrue(pointWithinPolygon(doubleArrayOf(1.0, 5.0), withHole))
        assertFalse(pointWithinPolygon(doubleArrayOf(5.0, 5.0), withHole))
    }

    @Test
    fun `pointWithinPolygons accepts any of the polygons`() {
        val far = listOf(ring(20.0 to 20.0, 30.0 to 20.0, 30.0 to 30.0, 20.0 to 30.0, 20.0 to 20.0))
        val polygons = listOf(square, far)
        assertTrue(pointWithinPolygons(doubleArrayOf(5.0, 5.0), polygons))
        assertTrue(pointWithinPolygons(doubleArrayOf(25.0, 25.0), polygons))
        assertFalse(pointWithinPolygons(doubleArrayOf(15.0, 15.0), polygons))
    }

    // endregion

    // region segments

    @Test
    fun `segmentIntersectSegment detects a crossing`() {
        assertTrue(
            segmentIntersectSegment(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 10.0),
                doubleArrayOf(0.0, 10.0), doubleArrayOf(10.0, 0.0),
            )
        )
    }

    @Test
    fun `parallel segments never intersect`() {
        assertFalse(
            segmentIntersectSegment(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0),
                doubleArrayOf(0.0, 5.0), doubleArrayOf(10.0, 5.0),
            )
        )
        // Collinear counts as parallel, so it does not intersect either.
        assertFalse(
            segmentIntersectSegment(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0),
                doubleArrayOf(5.0, 0.0), doubleArrayOf(15.0, 0.0),
            )
        )
    }

    @Test
    fun `disjoint segments do not intersect`() {
        assertFalse(
            segmentIntersectSegment(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0),
                doubleArrayOf(5.0, 5.0), doubleArrayOf(6.0, 6.0),
            )
        )
    }

    /** Upstream's `twoSided` needs strict sides, so a segment merely touching does not count. */
    @Test
    fun `a segment that only touches another does not intersect`() {
        assertFalse(
            segmentIntersectSegment(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0),
                doubleArrayOf(5.0, 0.0), doubleArrayOf(5.0, 10.0),
            )
        )
    }

    @Test
    fun `lineStringWithinPolygon requires every vertex inside and no crossing`() {
        assertTrue(lineStringWithinPolygon(ring(2.0 to 2.0, 8.0 to 8.0), square))
        assertFalse(lineStringWithinPolygon(ring(2.0 to 2.0, 20.0 to 20.0), square))
        assertFalse(lineStringWithinPolygon(ring(-5.0 to 5.0, 15.0 to 5.0), square))
    }

    // endregion
}
