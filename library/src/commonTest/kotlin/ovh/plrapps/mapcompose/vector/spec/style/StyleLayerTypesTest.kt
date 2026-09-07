package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.data.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every layer type the style spec defines decodes as part of a whole style.
 *
 * [Layer] is a sealed hierarchy keyed by a `type` discriminator, so a type with no subclass does not
 * decode to "some layer with unknown properties" -- it throws, and takes the *whole style* with it,
 * which means the map fails to load rather than losing one layer. `color-relief` was missing for
 * exactly that long, so this asserts the full list rather than one type.
 */
class StyleLayerTypesTest {

    @Test
    fun `a style carrying every layer type loads`() {
        val layers = TYPES.joinToString(",") { """{"id":"$it","type":"$it","source":"s"}""" }
        val style = json.decodeFromString(
            MapLibreStyle.serializer(),
            """{"version":8,"name":"every type","sources":{},"layers":[$layers]}""",
        )

        assertEquals(TYPES, style.layers.map { it.type })
        assertTrue(style.layers.any { it is ColorReliefLayer }, "color-relief is one of them")
    }

    private companion object {
        /** `v8.json`'s `layer.type` enum, in its own order. */
        val TYPES = listOf(
            "fill", "line", "symbol", "circle", "heatmap", "fill-extrusion", "raster", "hillshade",
            "color-relief", "background", "sky",
        )
    }
}
