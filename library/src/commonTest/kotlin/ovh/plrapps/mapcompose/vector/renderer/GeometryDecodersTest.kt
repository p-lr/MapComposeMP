package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.renderer.utils.isInsideTile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ovh.plrapps.mapcompose.vector.renderer.GeometryDecoders.Companion.tileCoordToCanvas
import ovh.plrapps.mapcompose.vector.renderer.GeometryDecoders.Companion.decodeZigZag

class GeometryDecodersTest {
    private fun encodeZigZag(n: Int): Int = (n shl 1) xor (n shr 31)

    private val decoders = GeometryDecoders()

    @Test
    fun testDecodeZigZag() {
        val values = listOf(0, 1, -1, 2, -2, 123456, -123456, Int.MAX_VALUE / 2, -(Int.MAX_VALUE / 2) - 1)
        for (n in values) {
            val encoded = encodeZigZag(n)
            assertEquals(n, decodeZigZag(encoded), "decodeZigZag(encodeZigZag($n))")
        }
    }

    @Test
    fun testTileCoordToCanvas() {
        // canvasSize = 256, extent = 4096, scale = 0.0625
        assertEquals(Pair(0f, 0f), tileCoordToCanvas(0, 0, 256, 4096))
        assertEquals(Pair(62.5f, 125f), tileCoordToCanvas(1000, 2000, 256, 4096))
        assertEquals(Pair(-6.25f, -6.25f), tileCoordToCanvas(-100, -100, 256, 4096))
        assertEquals(Pair(0f, -16f), tileCoordToCanvas(0, -16, 16, 16))
        assertEquals(Pair(250.625f, -1f), tileCoordToCanvas(4010, -16, 256, 4096))
    }

    @Test
    fun `polygon rings are closed and scaled to canvas space`() {
        val feature = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 2048, 2048))

        val rings = decoders.decodePolygon(feature.geometry, extent = 4096, canvasSize = 256)

        assertEquals(1, rings.size)
        // Four corners plus the repeated first point that closes the ring.
        assertEquals(5, rings[0].size)
        assertEquals(Pair(0f, 0f), rings[0].first())
        assertEquals(rings[0].first(), rings[0].last())
        assertTrue(rings[0].contains(Pair(128f, 128f)), "2048/4096 * 256 should be 128: ${rings[0]}")
    }

    @Test
    fun `a ring left open by a missing ClosePath is still decoded`() {
        // Same command stream as a polygon, minus the trailing ClosePath.
        val closed = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 1024, 1024))
        val open = closed.geometry.dropLast(1)

        val rings = decoders.decodePolygon(open, extent = 4096, canvasSize = 256)

        assertEquals(1, rings.size, "the unterminated ring must not be dropped")
        assertEquals(rings[0].first(), rings[0].last(), "and it must still be closed")
    }

    @Test
    fun `a MoveTo starts a new ring without discarding the previous one`() {
        val outer = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 2048, 2048))
        val inner = Mvt.polygonFeature(Mvt.clockwiseRing(2048, 2048, 4096, 4096))
        // Two rings, with the ClosePath of the first removed so only a MoveTo separates them.
        val geometry = outer.geometry.dropLast(1) + inner.geometry

        val rings = decoders.decodePolygon(geometry, extent = 4096, canvasSize = 256)

        assertEquals(2, rings.size)
    }

    @Test
    fun `decodePolygons groups a hole with its exterior ring`() {
        val feature = Mvt.polygonFeature(
            Mvt.clockwiseRing(0, 0, 4096, 4096),
            Mvt.counterClockwiseRing(1024, 1024, 3072, 3072),
        )

        val polygons = decoders.decodePolygons(feature.geometry, extent = 4096, canvasSize = 256)

        assertEquals(1, polygons.size, "one polygon, not two")
        assertEquals(2, polygons[0].size, "exterior ring plus its hole")
    }

    @Test
    fun `decodePolygons splits a multipolygon on winding order`() {
        val feature = Mvt.polygonFeature(
            Mvt.clockwiseRing(0, 0, 1024, 1024),
            Mvt.clockwiseRing(2048, 2048, 3072, 3072),
        )

        val polygons = decoders.decodePolygons(feature.geometry, extent = 4096, canvasSize = 256)

        assertEquals(2, polygons.size, "two exterior rings are two polygons")
        assertEquals(1, polygons[0].size)
        assertEquals(1, polygons[1].size)
    }

    @Test
    fun `a truncated command stream decodes what it can`() {
        val feature = Mvt.lineFeature(listOf(0 to 0, 1024 to 0, 1024 to 1024))
        // Drop the last parameter pair, leaving a LineTo that promises more than it delivers.
        val truncated = feature.geometry.dropLast(2)

        val lines = decoders.decodeLine(truncated, extent = 4096, canvasSize = 256)

        assertEquals(1, lines.size)
        assertTrue(lines[0].size >= 2, "the intact prefix should survive: ${lines[0]}")
    }

    @Test
    fun `multipoint features decode every point`() {
        val feature = Mvt.pointFeature(0 to 0, 2048 to 2048, 4096 to 0)

        val points = decoders.decodePoint(feature.geometry, extent = 4096, canvasSize = 256)

        assertEquals(3, points.size)
        assertEquals(Point(0.0, 0.0), points[0])
        assertEquals(Point(128.0, 128.0), points[1])
        assertEquals(Point(256.0, 0.0), points[2])
    }

    @Test
    fun `a non standard extent scales geometry accordingly`() {
        val feature = Mvt.pointFeature(512 to 256)

        val points = decoders.decodePoint(feature.geometry, extent = 1024, canvasSize = 512)

        assertEquals(Point(256.0, 128.0), points.single())
    }

    @Test
    fun `decodeVertices lists every vertex of a line`() {
        // Upstream's CircleBucket walks all of them, not just the first MoveTo.
        val feature = Mvt.lineFeature(listOf(0 to 0, 2048 to 0, 2048 to 2048))
        val vertices = decoders.decodeVertices(feature.geometry, extent = 4096, canvasSize = 256)
        assertEquals(3, vertices.size)
        assertEquals(0.0, vertices[0].x)
        assertEquals(128.0, vertices[1].x)
        assertEquals(128.0, vertices[2].y)
    }

    @Test
    fun `decodeVertices lists every vertex of a polygon ring`() {
        val feature = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 2048, 2048))
        val vertices = decoders.decodeVertices(feature.geometry, extent = 4096, canvasSize = 256)
        // Four corners; the ClosePath repeats the first and is not listed again.
        assertEquals(4, vertices.size)
    }

    @Test
    fun `decodeVertices lists every point of a multipoint`() {
        val feature = Mvt.pointFeature(0 to 0, 2048 to 2048)
        assertEquals(2, decoders.decodeVertices(feature.geometry, extent = 4096, canvasSize = 256).size)
    }

    @Test
    fun `a point outside the tile does not belong to it`() {
        assertTrue(isInsideTile(0.0, 0.0, 256))
        assertTrue(isInsideTile(255.9, 255.9, 256))
        assertFalse(isInsideTile(-0.1, 10.0, 256))
        assertFalse(isInsideTile(10.0, -0.1, 256))
        // The far edges belong to the next tile, so neighbours never both claim a point.
        assertFalse(isInsideTile(256.0, 10.0, 256))
        assertFalse(isInsideTile(10.0, 256.0, 256))
    }
}
