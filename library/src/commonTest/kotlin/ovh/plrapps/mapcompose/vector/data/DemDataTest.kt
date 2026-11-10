package ovh.plrapps.mapcompose.vector.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests the Terrain-RGB decode and the border ring.
 *
 * [DemData] takes an `IntArray` rather than an `ImageBitmap` precisely so these can live in
 * `commonTest`, where `ImageBitmap` cannot be allocated -- the same split
 * `RasterColorMatrixTest` uses.
 *
 * The border is not cosmetic: the Sobel operator reads one sample beyond the tile at every edge, so
 * what the ring holds decides whether neighbouring tiles agree about the slope between them.
 */
class DemDataTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A tile whose every pixel is the same encoded value. */
    private fun flatTile(dim: Int, pixel: Int): IntArray = IntArray(dim * dim) { pixel }

    @Test
    fun `mapbox encoding decodes to metres`() {
        // -10000 + (r * 65536 + g * 256 + b) * 0.1
        val pixels = intArrayOf(argb(1, 173, 176))
        val dem = assertNotNull(DemData.fromArgb(pixels, 1, 1, DemUnpack.MAPBOX))

        assertEquals(1000.0, dem[0, 0].toDouble(), 0.01)
    }

    @Test
    fun `mapbox encoding decodes sea level`() {
        val pixels = intArrayOf(argb(1, 134, 160))
        val dem = assertNotNull(DemData.fromArgb(pixels, 1, 1, DemUnpack.MAPBOX))

        assertEquals(0.0, dem[0, 0].toDouble(), 0.01)
    }

    @Test
    fun `terrarium encoding decodes to metres`() {
        // (r * 256 + g + b / 256) - 32768
        val pixels = intArrayOf(argb(131, 232, 128))
        val dem = assertNotNull(DemData.fromArgb(pixels, 1, 1, DemUnpack.TERRARIUM))

        assertEquals(1000.5, dem[0, 0].toDouble(), 0.01)
    }

    @Test
    fun `custom factors are applied as given`() {
        val unpack = DemUnpack(red = 2.0, green = 3.0, blue = 4.0, baseShift = 5.0)
        val pixels = intArrayOf(argb(10, 20, 30))
        val dem = assertNotNull(DemData.fromArgb(pixels, 1, 1, unpack))

        assertEquals(10 * 2.0 + 20 * 3.0 + 30 * 4.0 - 5.0, dem[0, 0].toDouble(), 0.01)
    }

    @Test
    fun `the border is seeded by clamping to the nearest interior sample`() {
        val dim = 4
        val pixels = IntArray(dim * dim) { i -> argb(0, 0, i % dim) }
        val unpack = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)
        val dem = assertNotNull(DemData.fromArgb(pixels, dim, dim, unpack))

        for (y in 0 until dim) {
            assertEquals(dem[0, y], dem[-1, y], "left border clamps to column 0")
            assertEquals(dem[dim - 1, y], dem[dim, y], "right border clamps to the last column")
        }
        for (x in 0 until dim) {
            assertEquals(dem[x, 0], dem[x, -1], "top border clamps to row 0")
            assertEquals(dem[x, dim - 1], dem[x, dim], "bottom border clamps to the last row")
        }
        assertEquals(dem[0, 0], dem[-1, -1])
        assertEquals(dem[dim - 1, 0], dem[dim, -1])
        assertEquals(dem[0, dim - 1], dem[-1, dim])
        assertEquals(dem[dim - 1, dim - 1], dem[dim, dim])
    }

    @Test
    fun `backfillBorder replaces the ring with the neighbour's edge`() {
        val dim = 4
        val unpack = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)
        val centre = assertNotNull(DemData.fromArgb(flatTile(dim, argb(0, 0, 10)), dim, dim, unpack))
        val east = assertNotNull(DemData.fromArgb(flatTile(dim, argb(0, 0, 200)), dim, dim, unpack))

        assertEquals(10f, centre[dim, 0], "the seed clamps to this tile before any backfill")
        centre.backfillBorder(east, dx = 1, dy = 0)

        for (y in 0 until dim) {
            assertEquals(200f, centre[dim, y], "the east border now holds the neighbour's data")
        }
        assertEquals(10f, centre[-1, 0], "the opposite border is untouched")
        assertEquals(10f, centre[dim - 1, 0], "the interior is untouched")
    }

    @Test
    fun `backfillBorder handles every offset`() {
        val dim = 4
        val unpack = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)
        val centre = assertNotNull(DemData.fromArgb(flatTile(dim, argb(0, 0, 0)), dim, dim, unpack))

        var value = 1
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbour = assertNotNull(
                    DemData.fromArgb(flatTile(dim, argb(0, 0, value)), dim, dim, unpack)
                )
                centre.backfillBorder(neighbour, dx, dy)
                value++
            }
        }

        // Every corner and edge of the ring now carries some neighbour's value, not the seed.
        assertEquals(1f, centre[-1, -1])
        assertEquals(3f, centre[dim, -1])
        assertEquals(6f, centre[-1, dim])
        assertEquals(8f, centre[dim, dim])
        assertEquals(2f, centre[0, -1])
        assertEquals(4f, centre[-1, 0])
        assertEquals(5f, centre[dim, 0])
        assertEquals(7f, centre[0, dim])
    }

    @Test
    fun `coordinates outside the border are rejected`() {
        val dem = assertNotNull(
            DemData.fromArgb(flatTile(2, argb(0, 0, 0)), 2, 2, DemUnpack.MAPBOX)
        )

        kotlin.test.assertFailsWith<IllegalArgumentException> { dem[-2, 0] }
        kotlin.test.assertFailsWith<IllegalArgumentException> { dem[0, 3] }
    }

    @Test
    fun `a non-square tile is refused rather than decoded`() {
        assertNull(DemData.fromArgb(IntArray(8), width = 4, height = 2, unpack = DemUnpack.MAPBOX))
        assertNull(DemData.fromArgb(IntArray(0), width = 0, height = 0, unpack = DemUnpack.MAPBOX))
    }
}
