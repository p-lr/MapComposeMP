package ovh.plrapps.mapcompose.vector.core

import ovh.plrapps.mapcompose.vector.renderer.Mvt
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the decoded-tile and geometry caches weigh an entry at. */
class DecodedSizeEstimateTest {

    private val square = Mvt.clockwiseRing(0, 0, 100, 100)
    private val line = listOf(0 to 0, 50 to 50, 100 to 0)

    @Test
    fun `a decoded tile weighs its integers and a per-feature overhead`() {
        val polygon = Mvt.polygonFeature(square)
        val road = Mvt.lineFeature(line, tags = listOf(0, 0))
        val tile = Mvt.tile(Mvt.layer(features = listOf(polygon, road)))

        val ints = polygon.geometry.size + road.geometry.size + road.tags.size
        assertEquals(ints * 4L + 2 * decodedFeatureBytes(), estimatedTileBytes(tile))
    }

    @Test
    fun `every polygon adds the fixed cost of its fill path`() {
        val lines = Mvt.tile(Mvt.layer(features = listOf(Mvt.lineFeature(line))))
        val polygons = Mvt.tile(
            Mvt.layer(features = listOf(Mvt.polygonFeature(square), Mvt.polygonFeature(square))),
        )

        // A line tile has no fill path to pay for, whatever the platform.
        assertEquals(estimatedTileBytes(lines) * 2, estimatedGeometryBytes(lines))
        assertEquals(estimatedTileBytes(polygons) * 2 + 2 * fillPathBytes(), estimatedGeometryBytes(polygons))
    }
}
