package ovh.plrapps.mapcompose.vector.data.geojson

import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.GeometryDecoders
import ovh.plrapps.mapcompose.vector.spec.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `geojson` source options that are resolved when the style loads: `filter`, `generateId`,
 * `buffer` and `tolerance`.
 *
 * All four used to be dropped on the floor -- `json` has `ignoreUnknownKeys = true`, so an
 * unmodelled source property is not a parse error, it is silence. The filter is upstream's
 * `_filterGeoJSON` (`src/source/geojson_worker_source.ts`), which runs in the worker *before*
 * geojson-vt sees the document; `generateId` is `convert.js`'s
 * `else if (options.generateId) id = index || 0`, which indexes what the filter left behind; and
 * both geometry options are style pixels converted by upstream's `_pixelsToTileUnits`.
 */
class GeoJsonSourceTest {

    private val decoders = GeometryDecoders()

    private fun style(options: String, features: String) = """
        {
          "version": 8,
          "sources": {
            "places": {
              "type": "geojson",
              $options
              "data": {"type": "FeatureCollection", "features": [$features]}
            }
          },
          "layers": []
        }
    """.trimIndent()

    private fun point(name: String, kind: String, id: String? = null) = """
        {
          "type": "Feature",
          ${if (id == null) "" else "\"id\": $id,"}
          "properties": {"name": "$name", "kind": "$kind"},
          "geometry": {"type": "Point", "coordinates": [0, 0]}
        }
    """.trimIndent()

    private suspend fun source(options: String = "", features: String) =
        getMapLibreConfiguration(style = style(options, features)) { null }
            .getOrThrow()
            .geoJsonSources["places"]

    /** Every feature of the world tile, as `(name, id)` pairs in the order the tiler emitted them. */
    private fun GeoJsonSource.worldFeatures(): List<Pair<String?, Long?>> {
        val layer = assertNotNull(tile(0, 0, 0)).layers.single()
        return layer.features.map { feature -> layer.nameOf(feature) to feature.id }
    }

    private fun Tile.Layer.nameOf(feature: Tile.Feature): String? {
        for (i in feature.tags.indices step 2) {
            if (keys[feature.tags[i]] == "name") return values[feature.tags[i + 1]].stringValue
        }
        return null
    }

    // region filter

    @Test
    fun `a source filter keeps only the features it accepts`() = runTest {
        val source = assertNotNull(
            source(
                options = """"filter": ["==", ["get", "kind"], "park"],""",
                features = listOf(
                    point("Bern", "city"),
                    point("Gurten", "park"),
                    point("Zurich", "city"),
                ).joinToString(","),
            )
        )

        assertEquals(listOf("Gurten"), source.worldFeatures().map { it.first })
    }

    /**
     * Legacy v7 filter syntax reaches the same `convertLegacyFilter` a layer filter's does, because
     * a source filter is compiled by the very same serializer.
     */
    @Test
    fun `a legacy source filter is converted like a layer filter`() = runTest {
        val source = assertNotNull(
            source(
                options = """"filter": ["==", "kind", "park"],""",
                features = listOf(point("Bern", "city"), point("Gurten", "park")).joinToString(","),
            )
        )

        assertEquals(listOf("Gurten"), source.worldFeatures().map { it.first })
    }

    @Test
    fun `a source filter reads the geometry type`() = runTest {
        val source = assertNotNull(
            source(
                options = """"filter": ["==", ["geometry-type"], "Point"],""",
                features = """
                    ${point("Bern", "city")},
                    {"type": "Feature", "properties": {"name": "border"},
                     "geometry": {"type": "LineString", "coordinates": [[0,0],[1,1]]}}
                """.trimIndent(),
            )
        )

        assertEquals(listOf("Bern"), source.worldFeatures().map { it.first })
    }

    @Test
    fun `a source filter that excludes everything leaves no source at all`() = runTest {
        assertNull(
            source(
                options = """"filter": ["==", ["get", "kind"], "lake"],""",
                features = listOf(point("Bern", "city"), point("Gurten", "park")).joinToString(","),
            )
        )
    }

    /**
     * A filter that fails to compile is reported and decodes to "no filter", which is the lenient
     * posture every layer filter already has: an unusable filter must not hide a source's features.
     */
    @Test
    fun `a malformed source filter is a diagnostic and keeps every feature`() = runTest {
        val configuration = getMapLibreConfiguration(
            style = style(
                options = """"filter": ["==", ["get", "kind"]],""",
                features = listOf(point("Bern", "city"), point("Gurten", "park")).joinToString(","),
            )
        ) { null }.getOrThrow()

        assertTrue(configuration.diagnostics.isNotEmpty(), "the broken filter must be reported")
        val source = assertNotNull(configuration.geoJsonSources["places"])
        assertEquals(listOf("Bern", "Gurten"), source.worldFeatures().map { it.first })
    }

    // endregion

    // region generateId

    @Test
    fun `generateId numbers the features from zero`() = runTest {
        val source = assertNotNull(
            source(
                options = """"generateId": true,""",
                features = listOf(point("Bern", "city"), point("Gurten", "park")).joinToString(","),
            )
        )

        assertEquals(listOf("Bern" to 0L, "Gurten" to 1L), source.worldFeatures())
    }

    /**
     * Upstream indexes the *filtered* array -- `_filterGeoJSON` runs in the worker and geojson-vt
     * converts what it returned -- so a feature the filter dropped must not consume an id. This is
     * why the filter is applied to the JSON document rather than to the parsed feature list.
     */
    @Test
    fun `generateId numbers only the features that survived the filter`() = runTest {
        val source = assertNotNull(
            source(
                options = """"generateId": true, "filter": ["==", ["get", "kind"], "park"],""",
                features = listOf(
                    point("Bern", "city"),
                    point("Gurten", "park"),
                    point("Zurich", "city"),
                    point("Uetliberg", "park"),
                ).joinToString(","),
            )
        )

        assertEquals(listOf("Gurten" to 0L, "Uetliberg" to 1L), source.worldFeatures())
    }

    /** `convert.js` assigns `id = index` outright; it does not fall back to the document's own. */
    @Test
    fun `generateId replaces an id the document wrote`() = runTest {
        val source = assertNotNull(
            source(
                options = """"generateId": true,""",
                features = listOf(point("Bern", "city", id = "77")).joinToString(","),
            )
        )

        assertEquals(listOf("Bern" to 0L), source.worldFeatures())
    }

    @Test
    fun `without generateId a feature keeps the id the document wrote`() = runTest {
        val source = assertNotNull(
            source(features = listOf(point("Bern", "city", id = "77")).joinToString(","))
        )

        assertEquals(listOf("Bern" to 77L), source.worldFeatures())
    }

    // endregion

    // region buffer and tolerance

    private val equator =
        """{"type": "Feature", "properties": {"name": "equator"},
            "geometry": {"type": "LineString", "coordinates": [[-180,0],[180,0]]}}"""

    /** How far past the tile's own `0..4096` the cut geometry reaches, in tile units. */
    private suspend fun overhang(options: String): Double {
        val source = assertNotNull(source(options = options, features = equator))
        val feature = assertNotNull(source.tile(1, 0, 0)).layers.single().features.single()
        val vertices = decoders.decodeVertices(feature.geometry, extent = 4096, canvasSize = 4096)
        return vertices.maxOf { it.x } - 4096.0
    }

    /**
     * The spec's default is 128 style pixels, which `_pixelsToTileUnits` turns into a quarter of a
     * tile -- 1024 units at extent 4096, not the flat 64 this port used to cut at.
     */
    @Test
    fun `the default buffer is a quarter of a tile on each side`() = runTest {
        assertEquals(1024.0, GeoJsonTiler.DEFAULT_BUFFER)
        assertEquals(3.0, GeoJsonTiler.DEFAULT_TOLERANCE)
        assertTrue(overhang(options = "") > 1000.0, "the default buffer must reach a quarter tile")
    }

    @Test
    fun `a source buffer is read in style pixels`() = runTest {
        assertTrue(overhang(options = """"buffer": 0,""") < 1.0)
        // 256 style pixels is half a tile; 128 -- the default -- is a quarter of one.
        assertTrue(overhang(options = """"buffer": 256,""") > 2000.0)
    }

    @Test
    fun `a buffer beyond the spec's range is clamped rather than obeyed`() = runTest {
        // The spec allows 0..512 style pixels, i.e. at most one whole tile on each side.
        assertTrue(overhang(options = """"buffer": 9000,""") <= 4096.0)
    }

    private suspend fun vertexCount(options: String, z: Int): Int {
        // Twenty collinear points across the world: everything between the ends is redundant.
        val coordinates = (0..20).joinToString(",") { "[${-180.0 + it * 18.0},0]" }
        val source = assertNotNull(
            source(
                options = options,
                features = """{"type": "Feature", "properties": {"name": "line"},
                    "geometry": {"type": "LineString", "coordinates": [$coordinates]}}""",
            )
        )
        val feature = assertNotNull(source.tile(z, 0, 0)).layers.single().features.single()
        return decoders.decodeVertices(feature.geometry, extent = 4096, canvasSize = 4096).size
    }

    @Test
    fun `a source tolerance of zero keeps every vertex`() = runTest {
        assertEquals(2, vertexCount(options = "", z = 0))
        assertEquals(21, vertexCount(options = """"tolerance": 0,""", z = 0))
    }

    /**
     * `splitTile`'s `z === options.maxZoom ? 0 : …`: the deepest level a source serves is never
     * simplified, because nothing below it will add the detail back.
     */
    @Test
    fun `the source's maxzoom is not simplified`() = runTest {
        assertEquals(21, vertexCount(options = """"maxzoom": 0,""", z = 0))
    }

    // endregion

    // region unsupported options

    @Test
    fun `clustering is reported rather than silently ignored`() = runTest {
        val configuration = getMapLibreConfiguration(
            style = style(options = """"cluster": true,""", features = point("Bern", "city"))
        ) { null }.getOrThrow()

        assertTrue(configuration.diagnostics.any { "clustering" in it.toString() })
        assertNotNull(configuration.geoJsonSources["places"], "the source is still tiled")
    }

    @Test
    fun `lineMetrics is reported rather than silently ignored`() = runTest {
        val configuration = getMapLibreConfiguration(
            style = style(options = """"lineMetrics": true,""", features = point("Bern", "city"))
        ) { null }.getOrThrow()

        assertTrue(configuration.diagnostics.any { "lineMetrics" in it.toString() })
        assertNotNull(configuration.geoJsonSources["places"], "the source is still tiled")
    }

    // endregion
}
