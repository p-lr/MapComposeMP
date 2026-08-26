package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Transcribed from `maplibre-style-spec/src/util/interpolate-primitives.test.ts`. */
class InterpolatePrimitivesUpstreamTest {

    @Test
    fun `interpolate number`() {
        assertEquals(-5.0, interpolateNumber(-5.0, 5.0, 0.0))
        assertEquals(-2.5, interpolateNumber(-5.0, 5.0, 0.25))
        assertEquals(0.0, interpolateNumber(-5.0, 5.0, 0.5))
        assertEquals(2.5, interpolateNumber(-5.0, 5.0, 0.75))
        assertEquals(5.0, interpolateNumber(-5.0, 5.0, 1.0))

        assertEquals(0.5, interpolateNumber(0.0, 1.0, 0.5))
        assertEquals(-7.5, interpolateNumber(-10.0, -5.0, 0.5))
        assertEquals(7.5, interpolateNumber(5.0, 10.0, 0.5))
    }

    @Test
    fun `interpolate array`() {
        assertContentEquals(
            doubleArrayOf(0.5, 1.0, 3 / 2.0, 2.0),
            interpolateArray(doubleArrayOf(0.0, 0.0, 0.0, 0.0), doubleArrayOf(1.0, 2.0, 3.0, 4.0), 0.5),
        )
    }
}
