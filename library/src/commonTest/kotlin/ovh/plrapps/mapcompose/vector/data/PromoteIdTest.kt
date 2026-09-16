package ovh.plrapps.mapcompose.vector.data

import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.spec.style.PromoteId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that a source's `promoteId` is read, in both the shapes the spec allows.
 *
 * The object form is the one that mattered: `Source.promoteId` was typed `String?`, so
 * `{"roads": "ref"}` -- which is what any multi-layer `vector` source writes -- threw out of the
 * decode, and `getMapLibreConfiguration` turns a decode failure into a `Result.failure` that blanks
 * the whole map. Nothing is thrown now; a shape the spec does not allow becomes a diagnostic.
 */
class PromoteIdTest {

    private fun style(promoteId: String) = """
        {
          "version": 8,
          "sources": {
            "basemap": {
              "type": "vector",
              "promoteId": $promoteId,
              "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
            }
          },
          "layers": []
        }
    """.trimIndent()

    @Test
    fun `the string form names one property for every source layer`() = runTest {
        val configuration = getMapLibreConfiguration(style = style("\"ref\"")) { null }.getOrThrow()

        val promoteId = assertNotNull(configuration.promoteIds["basemap"])
        assertEquals(PromoteId.Single("ref"), promoteId)
        assertEquals("ref", promoteId.propertyFor("roads"))
        assertEquals("ref", promoteId.propertyFor("anything else"))
        assertTrue(configuration.diagnostics.isEmpty())
    }

    @Test
    fun `the object form names one property per source layer`() = runTest {
        /* This is the case that used to fail the whole style rather than one source. */
        val configuration =
            getMapLibreConfiguration(style = style("""{"roads": "ref", "water": "wid"}""")) { null }
                .getOrThrow()

        val promoteId = assertNotNull(configuration.promoteIds["basemap"])
        assertEquals("ref", promoteId.propertyFor("roads"))
        assertEquals("wid", promoteId.propertyFor("water"))
        assertNull(promoteId.propertyFor("landuse"), "a layer the object does not name promotes nothing")
        assertNotNull(configuration.tileSources["basemap"], "the source is still registered")
        assertTrue(configuration.diagnostics.isEmpty())
    }

    @Test
    fun `a source declaring no promoteId has none`() = runTest {
        val style = """
            {
              "version": 8,
              "sources": {
                "basemap": {
                  "type": "vector",
                  "tiles": ["https://example.test/{z}/{x}/{y}.pbf"]
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        assertNull(configuration.promoteIds["basemap"])
        assertNotNull(configuration.tileSources["basemap"])
    }

    @Test
    fun `a geojson source promotes ids too`() = runTest {
        /* The read happens before the geojson branch returns, because the spec declares the
         * property on `source_geojson` as well as on `source_vector`. */
        val style = """
            {
              "version": 8,
              "sources": {
                "overlay": {
                  "type": "geojson",
                  "promoteId": "code",
                  "data": {"type": "FeatureCollection", "features": []}
                }
              },
              "layers": []
            }
        """.trimIndent()

        val configuration = getMapLibreConfiguration(style = style) { null }.getOrThrow()

        assertEquals(PromoteId.Single("code"), configuration.promoteIds["overlay"])
    }

    @Test
    fun `a malformed promoteId is reported rather than thrown`() = runTest {
        val configuration = getMapLibreConfiguration(style = style("7")) { null }.getOrThrow()

        assertNull(configuration.promoteIds["basemap"], "nothing is promoted")
        assertNotNull(configuration.tileSources["basemap"], "the source is still fetched")
        val diagnostic = assertNotNull(
            configuration.diagnostics.singleOrNull { it.location == "sources.basemap" },
            "the problem is recorded, so it is not silent: ${configuration.diagnostics}",
        )
        assertTrue("promoteId" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `an object entry that is not a property name is skipped and the rest kept`() = runTest {
        val configuration =
            getMapLibreConfiguration(style = style("""{"roads": "ref", "water": 3}""")) { null }
                .getOrThrow()

        val promoteId = assertNotNull(configuration.promoteIds["basemap"])
        assertEquals("ref", promoteId.propertyFor("roads"))
        assertNull(promoteId.propertyFor("water"))
        assertNotNull(configuration.diagnostics.singleOrNull { it.location == "sources.basemap" })
    }
}
