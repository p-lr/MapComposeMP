package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Transcribed from `maplibre-style-spec/src/util/classify_rings.test.ts`.
 *
 * Only the `classified.length` case applies: the other three exercise the `maxRings` quickselect
 * pruning, which this port omits because its single caller (`Distance`) passes `maxRings = 0`.
 */
class ClassifyRingsUpstreamTest {

    private fun ring(vararg coords: Pair<Int, Int>): List<Point2D> =
        coords.map { Point2D(it.first.toDouble(), it.second.toDouble()) }

    @Test
    fun `classified length`() {
        // A single ring is one polygon with one ring.
        var classified = classifyRings(
            listOf(ring(0 to 0, 0 to 40, 40 to 40, 40 to 0, 0 to 0))
        )
        assertEquals(1, classified.size)
        assertEquals(1, classified[0].size)

        // Two rings with the same winding are two separate polygons.
        classified = classifyRings(
            listOf(
                ring(0 to 0, 0 to 40, 40 to 40, 40 to 0, 0 to 0),
                ring(60 to 0, 60 to 40, 100 to 40, 100 to 0, 60 to 0),
            )
        )
        assertEquals(2, classified.size)
        assertEquals(1, classified[0].size)
        assertEquals(1, classified[1].size)

        // An opposite-wound ring is a hole in the preceding polygon.
        classified = classifyRings(
            listOf(
                ring(0 to 0, 0 to 40, 40 to 40, 40 to 0, 0 to 0),
                ring(10 to 10, 20 to 10, 20 to 20, 10 to 10),
            )
        )
        assertEquals(1, classified.size)
        assertEquals(2, classified[0].size)
    }
}
