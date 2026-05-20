package ovh.plrapps.mapcompose.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class GeometryTest {
    @Test
    fun getDistanceTest() {
        val d = getDistance(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(1.0, d)

        val d2 = getDistance(1.0, 0.0, 0.0, 0.0, 2.0, 0.0)
        assertEquals(0.0, d2)

        val d3 = getDistance(1.0, 1.0, 0.0, 0.0, 2.0, 0.0)
        assertEquals(1.0, d3)

        val d4 = getDistance(-1.0, 0.0, 0.0, 0.0, 2.0, 0.0)
        assertEquals(1.0, d4)

        val d5 = getDistance(3.0, 0.0, 0.0, 0.0, 2.0, 0.0)
        assertEquals(1.0, d5)
    }
}