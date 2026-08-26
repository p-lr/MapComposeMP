package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.test.Test
import kotlin.test.assertEquals

/** Transcribed from `maplibre-style-spec/src/expression/stops.test.ts`. */
class StopsUpstreamTest {

    private val key = "layers[0].paint.line-width"

    @Test
    fun `When the input is greater than all stops it returns the last stop`() {
        assertEquals(7, findStopLessThanOrEqualTo(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), 8.0, key))
    }

    @Test
    fun `When more than one stop has the same value it always returns the last stop`() {
        assertEquals(1, findStopLessThanOrEqualTo(listOf(0.5, 0.5), 0.5, key))
        assertEquals(2, findStopLessThanOrEqualTo(listOf(0.5, 0.5, 0.5), 0.5, key))
        assertEquals(2, findStopLessThanOrEqualTo(listOf(0.4, 0.5, 0.5, 0.6, 0.7), 0.5, key))
    }
}
