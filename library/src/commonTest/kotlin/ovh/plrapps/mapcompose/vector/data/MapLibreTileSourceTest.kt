package ovh.plrapps.mapcompose.vector.data

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
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
 *
 * The clamp is the same for every source type, as it is in maplibre-gl-js -- `covering_tiles.ts`
 * knows nothing about what a source serves. What differs is what the renderer does with the
 * ancestor: an image source is stretched, a vector source is re-rendered at the display zoom.
 */
class MapLibreTileSourceTest {

    private fun source(
        minzoom: Int = 0,
        maxzoom: Int = 22,
        scheme: String = "xyz",
        type: SourceType = SourceType.RASTER,
        tiles: List<String> = listOf("https://example.test/{z}/{x}/{y}.png"),
        pixelRatio: () -> Float = { 1f },
    ) = MapLibreTileSource(
        TileJson(
            tilejson = "2.0.0",
            tiles = tiles,
            minzoom = minzoom,
            maxzoom = maxzoom,
            scheme = scheme,
        ),
        type,
        pixelRatio = pixelRatio,
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

    // region URL template tokens -- upstream's CanonicalTileID.url, src/tile/tile_id.ts

    @Test
    fun `a prefix token expands to two hex digits`() {
        // 31 % 16 is 15, which is 'f'; 18 % 16 is 2.
        val url = source(tiles = listOf("https://example.test/{prefix}/{z}/{x}/{y}.png"))
            .getTileUrl(z = 5, x = 31, y = 18)

        assertEquals("https://example.test/f2/5/31/18.png", url)
    }

    @Test
    fun `a quadkey token expands to one base four digit per zoom level`() {
        // Bing's own worked example -- z3 x3 y5 is quadkey 213.
        val url = source(tiles = listOf("https://example.test/{quadkey}.png"))
            .getTileUrl(z = 3, x = 3, y = 5)

        assertEquals("https://example.test/213.png", url)
    }

    @Test
    fun `a quadkey at zoom zero is empty`() {
        val url = source(tiles = listOf("https://example.test/q{quadkey}.png"))
            .getTileUrl(z = 0, x = 0, y = 0)

        assertEquals("https://example.test/q.png", url)
    }

    @Test
    fun `a ratio token is empty below a pixel ratio of two`() {
        val url = source(tiles = listOf("https://example.test/{z}/{x}/{y}{ratio}.png"))
            .getTileUrl(z = 4, x = 3, y = 2)

        assertEquals("https://example.test/4/3/2.png", url)
    }

    @Test
    fun `a ratio token is read at call time rather than at construction`() {
        /* The density only arrives once MapUI has composed, which is after the style is decoded and
         * every MapLibreTileSource built -- so a ratio baked in at construction is always 1. */
        var density = 1f
        val tileSource = source(
            tiles = listOf("https://example.test/{z}/{x}/{y}{ratio}.png"),
            pixelRatio = { density },
        )

        assertEquals("https://example.test/4/3/2.png", tileSource.getTileUrl(z = 4, x = 3, y = 2))

        density = 2f
        assertEquals("https://example.test/4/3/2@2x.png", tileSource.getTileUrl(z = 4, x = 3, y = 2))
    }

    @Test
    fun `a bbox token at the north east quadrant of zoom one starts at the origin`() {
        /* z1 x1 y0 is the top-right tile, whose projected corners are exactly the origin and the
         * world's north-east corner -- every component lands on a value the double arithmetic holds
         * exactly, so this one can be asserted as a whole string. */
        val url = source(tiles = listOf("https://wms.test?bbox={bbox-epsg-3857}"))
            .getTileUrl(z = 1, x = 1, y = 0)

        assertEquals(
            "https://wms.test?bbox=0,0,20037508.342789244,20037508.342789244",
            url,
        )
    }

    @Test
    fun `a bbox token is four plain decimal numbers of projected metres`() {
        val url = source(tiles = listOf("https://wms.test?bbox={bbox-epsg-3857}"))
            .getTileUrl(z = 4, x = 3, y = 2)

        val components = url.substringAfter("bbox=").split(",")
        assertEquals(4, components.size)
        /* A WMS server is handed this text verbatim, and Double.toString reaches for exponent
         * notation above 1e7 on every target while JavaScript's String does not. Which shortest
         * representation a target picks is its own business, hence the tolerance -- that there is
         * no exponent at all is not. */
        for (component in components) {
            assertTrue('e' !in component && 'E' !in component, "exponent notation in $component")
        }
        // The z4 row 2 counted from the top is row 13 from the bottom; H is half the circumference.
        assertEquals(-12523442.714243278, components[0].toDouble(), 1e-6)
        assertEquals(12523442.714243278, components[1].toDouble(), 1e-6)
        assertEquals(-10018754.171394622, components[2].toDouble(), 1e-6)
        assertEquals(15028131.257091932, components[3].toDouble(), 1e-6)
    }

    @Test
    fun `the scheme mirrors the y token and leaves the bbox and the quadkey alone`() {
        /* Upstream builds both from this.y, before the scheme substitution -- and the bbox does its
         * own unconditional flip, which is a different thing from TileJSON's scheme. */
        val template = listOf("https://example.test/{y}?bbox={bbox-epsg-3857}&q={quadkey}")
        val xyz = source(tiles = template, scheme = "xyz").getTileUrl(z = 4, x = 3, y = 2)
        val tms = source(tiles = template, scheme = "tms").getTileUrl(z = 4, x = 3, y = 2)

        assertEquals("2", xyz.substringAfter(".test/").substringBefore("?"))
        assertEquals("13", tms.substringAfter(".test/").substringBefore("?"))
        assertEquals(xyz.substringAfter("?"), tms.substringAfter("?"))
    }

    @Test
    fun `the template of a tile is chosen by x plus y and is therefore stable`() {
        val tiles = listOf("https://a.test/{z}/{x}/{y}", "https://b.test/{z}/{x}/{y}", "https://c.test/{z}/{x}/{y}")
        val tileSource = source(tiles = tiles)

        // Same tile, same host, however often it is asked for -- which is what a cache needs.
        repeat(8) {
            // (3 + 2) % 3 is 2.
            assertEquals("https://c.test/4/3/2", tileSource.getTileUrl(z = 4, x = 3, y = 2))
        }
        // x + y stepping by one walks the list and wraps.
        assertEquals("https://a.test/4/4/2", tileSource.getTileUrl(z = 4, x = 4, y = 2))
        assertEquals("https://b.test/4/5/2", tileSource.getTileUrl(z = 4, x = 5, y = 2))
    }

    @Test
    fun `a template with no tokens beyond z x and y is untouched`() {
        val url = source().getTileUrl(z = 4, x = 3, y = 2)

        assertEquals("https://example.test/4/3/2.png", url)
    }

    // endregion

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
    fun `a vector source overzooms like a raster one`() {
        /* The behaviour this used to lack: above its maxzoom a vector source resolved to the tile it
         * was asked for, the server had none, and the layer went blank. MapLibre keeps the deepest
         * ancestor and re-parses it at the display zoom (`reparseOverscaled`). */
        val tileSource = source(maxzoom = 14, type = SourceType.VECTOR)

        val ref = assertNotNull(tileSource.resolve(z = 18, x = 137, y = 91))
        assertEquals(14, ref.z)
        assertEquals(137 shr 4, ref.x)
        assertEquals(91 shr 4, ref.y)
        assertEquals(16, ref.span)
        assertEquals(137 % 16, ref.subX)
        assertEquals(91 % 16, ref.subY)
        assertTrue(ref.isWholeTile.not(), "an overzoomed ref names a sub-square, not a whole tile")
    }

    @Test
    fun `an unrecognised source type is not treated as vector`() {
        assertEquals(SourceType.UNKNOWN, SourceType.fromSpec("something-new"))
        assertEquals(SourceType.VECTOR, SourceType.fromSpec(null))
        assertEquals(SourceType.RASTER_DEM, SourceType.fromSpec("raster-dem"))
    }

    /* ---- the TileJSON merge -------------------------------------------------------------- */

    /**
     * Serves one TileJSON document whatever is asked for, which is all these need: a style here has
     * a single `url` source.
     */
    private fun serving(tileJson: String): suspend (String) -> RawSource? = { _: String ->
        Buffer().apply { write(tileJson.encodeToByteArray()) }
    }

    private val servedTileJson = """
        {
          "tilejson": "2.0.0",
          "tiles": ["https://served.test/{z}/{x}/{y}.pbf"],
          "minzoom": 3,
          "maxzoom": 13,
          "scheme": "xyz"
        }
    """.trimIndent()

    @Test
    fun `explicit source options take precedence over the referenced TileJSON`() = runTest {
        /* Upstream's `extend(tileJSON, options)` (`load_tilejson.ts`), whose own comment says so.
         * These used to be discarded wholesale the moment a source carried a `url`, so a source
         * overriding a document to tms still addressed rows the document's way. */
        val style = """
            {
              "version": 8,
              "sources": {
                "basemap": {
                  "type": "vector",
                  "url": "https://example.test/tiles.json",
                  "tiles": ["https://overridden.test/{z}/{x}/{y}.pbf"],
                  "minzoom": 5,
                  "maxzoom": 18,
                  "scheme": "tms"
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration =
            getMapLibreConfiguration(style = style, loadResource = serving(servedTileJson)).getOrThrow()

        val basemap = assertNotNull(configuration.tileSources["basemap"])
        assertEquals(5, basemap.minZoom)
        assertEquals(18, basemap.maxZoom)
        assertEquals(
            "https://overridden.test/4/3/13.pbf",
            basemap.getTileUrl(z = 4, x = 3, y = 2),
            "the source's own tiles and its tms scheme, not the document's",
        )
    }

    @Test
    fun `a referenced TileJSON supplies what the source leaves out`() = runTest {
        /* The other direction, and the guard against applying the merge the wrong way round. */
        val style = """
            {
              "version": 8,
              "sources": {
                "basemap": {
                  "type": "vector",
                  "url": "https://example.test/tiles.json"
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration =
            getMapLibreConfiguration(style = style, loadResource = serving(servedTileJson)).getOrThrow()

        val basemap = assertNotNull(configuration.tileSources["basemap"])
        assertEquals(3, basemap.minZoom)
        assertEquals(13, basemap.maxZoom)
        assertEquals("https://served.test/4/3/2.pbf", basemap.getTileUrl(z = 4, x = 3, y = 2))
    }

    @Test
    fun `a dem source with no encoding takes the referenced TileJSON's`() = runTest {
        val style = """
            {
              "version": 8,
              "sources": {
                "terrain": {
                  "type": "raster-dem",
                  "url": "https://example.test/tiles.json"
                }
              },
              "layers": []
            }
        """.trimIndent()
        val tileJson = """
            {
              "tilejson": "2.0.0",
              "tiles": ["https://served.test/{z}/{x}/{y}.png"],
              "encoding": "terrarium"
            }
        """.trimIndent()

        val configuration =
            getMapLibreConfiguration(style = style, loadResource = serving(tileJson)).getOrThrow()

        assertEquals(
            DemUnpack.TERRARIUM,
            assertNotNull(configuration.tileSources["terrain"]).demUnpack,
            "only `encoding` travels from the document, and it does",
        )
    }

    @Test
    fun `a dem source's own encoding beats the referenced TileJSON's`() = runTest {
        val style = """
            {
              "version": 8,
              "sources": {
                "terrain": {
                  "type": "raster-dem",
                  "url": "https://example.test/tiles.json",
                  "encoding": "custom",
                  "redFactor": 2.0,
                  "greenFactor": 3.0,
                  "blueFactor": 4.0,
                  "baseShift": 5.0
                }
              },
              "layers": []
            }
        """.trimIndent()
        val tileJson = """
            {
              "tilejson": "2.0.0",
              "tiles": ["https://served.test/{z}/{x}/{y}.png"],
              "encoding": "terrarium"
            }
        """.trimIndent()

        val configuration =
            getMapLibreConfiguration(style = style, loadResource = serving(tileJson)).getOrThrow()

        assertEquals(
            DemUnpack(red = 2.0, green = 3.0, blue = 4.0, baseShift = 5.0),
            assertNotNull(configuration.tileSources["terrain"]).demUnpack,
            "the factors are not in upstream's pick list, so they stay the source's own",
        )
    }

    @Test
    fun `a vector source declaring the mlt encoding is refused rather than decoded as MVT`() = runTest {
        /* Upstream picks its decoder off `params.encoding` (`vector_tile_worker_source.ts`); this
         * port has no MLT decoder, and pbandk does not throw on foreign bytes -- it collects them
         * into `Tile.unknownFields` -- so an unrecognised source would have rendered a blank map
         * with nothing said. */
        val style = """
            {
              "version": 8,
              "sources": {
                "mlt-basemap": {
                  "type": "vector",
                  "encoding": "mlt",
                  "tiles": ["https://example.test/{z}/{x}/{y}.mlt"]
                },
                "mvt-basemap": {
                  "type": "vector",
                  "encoding": "mvt",
                  "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
                },
                "plain": {
                  "type": "vector",
                  "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        assertNull(
            configuration.tileSources["mlt-basemap"],
            "an mlt source is not registered, so it is never fetched either",
        )
        assertEquals(
            SourceType.VECTOR,
            assertNotNull(configuration.tileSources["mvt-basemap"]).type,
            "an explicit mvt encoding is the spec default and changes nothing",
        )
        assertNotNull(
            configuration.tileSources["plain"],
            "an absent encoding is mvt, as upstream's `params.encoding !== 'mlt'` says",
        )

        val diagnostic = assertNotNull(
            configuration.diagnostics.singleOrNull { it.location == "sources.mlt-basemap" },
            "the refusal is recorded, so it is not silent: ${configuration.diagnostics}",
        )
        assertTrue(
            "mlt" in diagnostic.message,
            "the diagnostic names the encoding, got ${diagnostic.message}",
        )
    }

    @Test
    fun `a referenced TileJSON declaring the mlt encoding refuses the source too`() = runTest {
        /* The encoding is read off the merged document for the same reason `DemUnpack` reads its
         * own off it: upstream's `params.encoding` comes out of `extend(tileJSON, options)`. */
        val servedMlt = """
            {
              "tilejson": "2.0.0",
              "tiles": ["https://served.test/{z}/{x}/{y}.mlt"],
              "minzoom": 3,
              "maxzoom": 13,
              "encoding": "mlt"
            }
        """.trimIndent()
        val style = """
            {
              "version": 8,
              "sources": {
                "basemap": {
                  "type": "vector",
                  "url": "https://example.test/tiles.json"
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration =
            getMapLibreConfiguration(style = style, loadResource = serving(servedMlt)).getOrThrow()

        assertNull(configuration.tileSources["basemap"])
        assertNotNull(configuration.diagnostics.singleOrNull { it.location == "sources.basemap" })
    }

    @Test
    fun `a raster-dem source keeps its own meaning of encoding`() = runTest {
        /* `encoding` is one JSON key serving two properties -- the DEM unpacking and the vector wire
         * format -- so the gate must not catch a raster-dem source whose encoding is terrarium. */
        val style = """
            {
              "version": 8,
              "sources": {
                "terrain": {
                  "type": "raster-dem",
                  "encoding": "terrarium",
                  "tiles": ["https://example.test/{z}/{x}/{y}.png"]
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        val terrain = assertNotNull(configuration.tileSources["terrain"])
        assertEquals(SourceType.RASTER_DEM, terrain.type)
        assertEquals(DemUnpack.TERRARIUM, terrain.demUnpack)
        assertTrue(configuration.diagnostics.isEmpty(), "nothing is refused")
    }
}
