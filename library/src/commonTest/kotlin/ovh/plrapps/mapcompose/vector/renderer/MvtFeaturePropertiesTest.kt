package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [MvtFeatureProperties] answers what the copied map `extractFeatureProperties` builds answers. */
class MvtFeaturePropertiesTest {

    private val renderer = object : BaseRenderer(
        MapLibreConfiguration(style = MapLibreStyle(), tileSources = emptyMap(), spriteManager = null)
    ) {}

    private val layer = Tile.Layer(
        version = 2,
        name = "l",
        keys = listOf("class", "rank", "name", "oneway", "class"),
        values = listOf(
            Tile.Value(stringValue = "road"),
            Tile.Value(intValue = 3),
            Tile.Value(floatValue = 1.5f),
            Tile.Value(boolValue = true),
            Tile.Value(uintValue = 7),
            Tile.Value(stringValue = "path"),
        ),
    )

    private fun assertSameAsCopy(tags: IntArray) {
        val feature = Tile.Feature(tags = tags)
        val copied = renderer.extractFeatureProperties(feature, layer)
        val view = MvtFeatureProperties(layer, tags)
        assertEquals<Map<String, Any?>>(copied, view, "equal as maps")
        assertEquals(copied.keys.toList(), view.keys.toList(), "same iteration order")
        for (key in layer.keys + "absent") {
            assertEquals(copied[key], view[key], "get($key)")
            assertEquals(copied.containsKey(key), view.containsKey(key), "containsKey($key)")
        }
    }

    @Test
    fun `plain tags read the same`() = assertSameAsCopy(intArrayOf(0, 0, 1, 1, 2, 2, 3, 3))

    @Test
    fun `numbers come out as doubles`() {
        val view = MvtFeatureProperties(layer, intArrayOf(1, 1, 2, 2, 0, 4))
        assertEquals(3.0, view["rank"])
        assertEquals(1.5, view["name"])
        assertEquals(7.0, view["class"])
    }

    @Test
    fun `a key repeated in the tags takes its last value`() = assertSameAsCopy(intArrayOf(0, 0, 1, 1, 0, 5))

    @Test
    fun `a key repeated in the key table is found through either index`() {
        assertSameAsCopy(intArrayOf(4, 5, 1, 1))
        assertSameAsCopy(intArrayOf(0, 0, 4, 5))
        assertSameAsCopy(intArrayOf(4, 5, 0, 0))
    }

    @Test
    fun `out of range indices and a dangling key are skipped`() {
        assertSameAsCopy(intArrayOf(0, 99, 99, 1, 1, 1))
        assertSameAsCopy(intArrayOf(0, 0, 1))
        val view = MvtFeatureProperties(layer, intArrayOf(0, 99))
        assertNull(view["class"])
        assertFalse(view.containsKey("class"))
    }

    @Test
    fun `no tags is no properties`() {
        val view = MvtFeatureProperties(layer, IntArray(0))
        assertTrue(view.isEmpty())
        assertSameAsCopy(IntArray(0))
    }
}
