package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.data.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A layer's `paint` and `layout` are never null, as upstream's `StyleLayer` always builds both
 * from the style spec (`src/style/style_layer.ts`). Omitting the object, writing `{}` and writing
 * `null` all have to mean the same thing: every property at its spec default.
 */
class LayerDefaultsTest {

    @Test
    fun `an omitted paint decodes to the empty object`() {
        val layer = json.decodeFromString(
            Layer.serializer(),
            """{"id":"c","type":"circle","source":"s"}""",
        ) as CircleLayer

        assertEquals(json.decodeFromString(Layer.serializer(), EXPLICIT_EMPTY), layer)
        assertNull(layer.paint.circleRadius, "no property is set")
        assertEquals("visible", layer.layout.visibility)
    }

    @Test
    fun `an explicitly null paint is coerced to the empty object`() {
        // `coerceInputValues` in the shared Json config, so a style writing `"paint": null` -- which
        // styles in the wild do -- decodes rather than throwing.
        val layer = json.decodeFromString(
            Layer.serializer(),
            """{"id":"c","type":"circle","source":"s","paint":null,"layout":null}""",
        ) as CircleLayer

        assertEquals(json.decodeFromString(Layer.serializer(), EXPLICIT_EMPTY), layer)
    }

    @Test
    fun `every layer type defaults both objects`() {
        for (type in TYPES) {
            val layer = json.decodeFromString(
                Layer.serializer(),
                """{"id":"l","type":"$type","source":"s"}""",
            )

            val explicit = json.decodeFromString(
                Layer.serializer(),
                """{"id":"l","type":"$type","source":"s","paint":{},"layout":{}}""",
            )

            assertEquals(explicit, layer, "an omitted paint or layout is the empty object ($type)")
        }
    }

    private companion object {
        const val EXPLICIT_EMPTY =
            """{"id":"c","type":"circle","source":"s","paint":{},"layout":{}}"""

        val TYPES = listOf(
            "background", "circle", "color-relief", "fill", "fill-extrusion", "heatmap",
            "hillshade", "line", "raster", "sky", "symbol",
        )
    }
}
