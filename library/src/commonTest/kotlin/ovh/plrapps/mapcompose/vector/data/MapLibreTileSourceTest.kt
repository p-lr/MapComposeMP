package ovh.plrapps.mapcompose.vector.data

import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the source-type and tile-addressing plumbing a `raster` layer depends on.
 *
 * `Source.type` used to be parsed and dropped, so every source was fetched and pbf-decoded as MVT.
 * These pin the three things that changed: the type reaches [MapLibreTileSource], `scheme` is
 * honoured, and [MapLibreTileSource.resolve] clamps a request to the zooms a source publishes.
 */
class MapLibreTileSourceTest {

    private fun source(
        minzoom: Int = 0,
        maxzoom: Int = 22,
        scheme: String = "xyz",
        type: SourceType = SourceType.RASTER,
    ) = MapLibreTileSource(
        TileJson(
            tilejson = "2.0.0",
            tiles = listOf("https://example.test/{z}/{x}/{y}.png"),
            minzoom = minzoom,
            maxzoom = maxzoom,
            scheme = scheme,
        ),
        type,
    )

    @Test
    fun `a tile within the published zooms resolves to itself`() {
        val ref = assertNotNull(source(minzoom = 5, maxzoom = 14).resolve(z = 10, x = 3, y = 7))

        assertEquals(10, ref.z)
        assertEquals(3, ref.x)
        assertEquals(7, ref.y)
        assertEquals(1, ref.span, "no crop is needed when the source has the zoom")
        assertEquals(0, ref.subX)
        assertEquals(0, ref.subY)
    }

    @Test
    fun `the deepest published zoom still resolves to itself`() {
        val ref = assertNotNull(source(maxzoom = 14).resolve(z = 14, x = 100, y = 200))

        assertEquals(14, ref.z)
        assertEquals(1, ref.span)
    }

    @Test
    fun `below the shallowest published zoom there is nothing to draw`() {
        assertNull(source(minzoom = 5).resolve(z = 4, x = 1, y = 1))
    }

    @Test
    fun `one zoom past the deepest crops a quarter of the parent`() {
        // At z15 the tile (7, 9) sits inside the z14 tile (3, 4), in its bottom-left quarter.
        val ref = assertNotNull(source(maxzoom = 14).resolve(z = 15, x = 7, y = 9))

        assertEquals(14, ref.z)
        assertEquals(3, ref.x)
        assertEquals(4, ref.y)
        assertEquals(2, ref.span)
        assertEquals(1, ref.subX)
        assertEquals(1, ref.subY)
    }

    @Test
    fun `three zooms past the deepest crops a sixty fourth of the ancestor`() {
        val ref = assertNotNull(source(maxzoom = 10).resolve(z = 13, x = 45, y = 22))

        assertEquals(10, ref.z)
        assertEquals(45 shr 3, ref.x)
        assertEquals(22 shr 3, ref.y)
        assertEquals(8, ref.span)
        assertEquals(45 % 8, ref.subX)
        assertEquals(22 % 8, ref.subY)
    }

    @Test
    fun `every sub square of an overzoomed ancestor is distinct and in range`() {
        val ancestor = source(maxzoom = 12)
        val seen = mutableSetOf<Pair<Int, Int>>()
        // The four z13 children of the z12 tile (6, 9).
        for (x in 12..13) {
            for (y in 18..19) {
                val ref = assertNotNull(ancestor.resolve(z = 13, x = x, y = y))
                assertEquals(6, ref.x)
                assertEquals(9, ref.y)
                assertTrue(ref.subX in 0 until ref.span)
                assertTrue(ref.subY in 0 until ref.span)
                seen += ref.subX to ref.subY
            }
        }
        assertEquals(4, seen.size, "the four children should crop four different quarters")
    }

    @Test
    fun `an xyz source substitutes the row as given`() {
        val url = source(scheme = "xyz").getTileUrl(z = 4, x = 3, y = 2)
        assertEquals("https://example.test/4/3/2.png", url)
    }

    @Test
    fun `a tms source mirrors the row`() {
        // At z4 there are 16 rows, so row 2 from the top is row 13 from the bottom.
        val url = source(scheme = "tms").getTileUrl(z = 4, x = 3, y = 2)
        assertEquals("https://example.test/4/3/13.png", url)
    }

    @Test
    fun `the url of a resolved ref uses the ancestor coordinates`() {
        val tileSource = source(maxzoom = 14)
        val ref = assertNotNull(tileSource.resolve(z = 15, x = 7, y = 9))

        assertEquals("https://example.test/14/3/4.png", tileSource.getTileUrl(ref))
    }

    @Test
    fun `source types are read off the style`() = runTest {
        val style = """
            {
              "version": 8,
              "sources": {
                "basemap": {
                  "type": "raster",
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"],
                  "minzoom": 2,
                  "maxzoom": 18,
                  "scheme": "tms"
                },
                "overlay": {
                  "type": "vector",
                  "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
                },
                "untyped": {
                  "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        val basemap = assertNotNull(configuration.tileSources["basemap"])
        assertEquals(SourceType.RASTER, basemap.type)
        assertEquals(2, basemap.minZoom)
        assertEquals(18, basemap.maxZoom)
        assertEquals("https://example.test/4/3/13.png", basemap.getTileUrl(z = 4, x = 3, y = 2))

        assertEquals(SourceType.VECTOR, assertNotNull(configuration.tileSources["overlay"]).type)
        assertEquals(
            SourceType.VECTOR,
            assertNotNull(configuration.tileSources["untyped"]).type,
            "an absent type keeps the behaviour every style had before types were modelled",
        )
    }

    @Test
    fun `a raster-dem source overzooms like a raster one`() {
        val tileSource = source(maxzoom = 14, type = SourceType.RASTER_DEM)

        val ref = assertNotNull(tileSource.resolve(z = 16, x = 13, y = 6))
        assertEquals(14, ref.z)
        assertEquals(3, ref.x)
        assertEquals(1, ref.y)
        assertEquals(4, ref.span)
        assertEquals(1, ref.subX)
        assertEquals(2, ref.subY)
    }

    @Test
    fun `the dem unpack vector is read off the style`() = runTest {
        val style = """
            {
              "version": 8,
              "sources": {
                "terrarium": {
                  "type": "raster-dem",
                  "encoding": "terrarium",
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"]
                },
                "mapbox": {
                  "type": "raster-dem",
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"]
                },
                "made-up": {
                  "type": "raster-dem",
                  "encoding": "custom",
                  "redFactor": 2.0,
                  "greenFactor": 3.0,
                  "blueFactor": 4.0,
                  "baseShift": 5.0,
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"]
                },
                "plain": {
                  "type": "raster",
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"]
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        assertEquals(
            DemUnpack.TERRARIUM,
            assertNotNull(configuration.tileSources["terrarium"]).demUnpack,
        )
        assertEquals(
            DemUnpack.MAPBOX,
            assertNotNull(configuration.tileSources["mapbox"]).demUnpack,
            "an absent encoding is mapbox, as the style spec says",
        )
        assertEquals(
            DemUnpack(red = 2.0, green = 3.0, blue = 4.0, baseShift = 5.0),
            assertNotNull(configuration.tileSources["made-up"]).demUnpack,
        )
        assertNull(
            assertNotNull(configuration.tileSources["plain"]).demUnpack,
            "only a raster-dem source's channels mean elevation",
        )
    }

    @Test
    fun `an unrecognised source type is not treated as vector`() {
        assertEquals(SourceType.UNKNOWN, SourceType.fromSpec("something-new"))
        assertEquals(SourceType.VECTOR, SourceType.fromSpec(null))
        assertEquals(SourceType.RASTER_DEM, SourceType.fromSpec("raster-dem"))
    }
}
