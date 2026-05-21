package ovh.plrapps.mapcompose.demo.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class WebMercatorTest {
    @Test
    fun latLonToNormalizedTest() {
        val lat = 48.856667
        val lon = 2.351667
        val parisNormalized = latLonToNormalized(lat, lon)

        val parisLonLat = normalizedToLatLon(parisNormalized.first, parisNormalized.second)

        assertEquals(lat, parisLonLat.first, 0.00001)
        assertEquals(lon, parisLonLat.second, 0.00001)
    }
}