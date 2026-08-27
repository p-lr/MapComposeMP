package ovh.plrapps.mapcompose.vector.spec.sprites

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sprite sheet index format, https://maplibre.org/maplibre-style-spec/sprite/.
 *
 * `stretchX`/`stretchY` used to be typed as a pair of pairs, which no real sheet decodes into, and
 * `content`, `pixelRatio` and the `textFit*` hints were not modelled at all -- so a stretchable icon
 * could neither be parsed nor drawn.
 */
class SpriteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sprite(text: String): Sprite = json.decodeFromString(Sprite.serializer(), text)

    @Test
    fun `a minimal entry defaults to one sheet pixel per layout pixel`() {
        val sprite = sprite("""{"width":16,"height":16,"x":0,"y":0}""")
        assertEquals(1f, sprite.pixelRatio)
        assertEquals(16f, sprite.layoutWidth)
        assertEquals(16f, sprite.layoutHeight)
        assertNull(sprite.stretchX)
        assertNull(sprite.content)
    }

    @Test
    fun `a hidpi entry is half its sheet size in layout pixels`() {
        val sprite = sprite("""{"width":32,"height":48,"x":0,"y":0,"pixelRatio":2}""")
        assertEquals(16f, sprite.layoutWidth)
        assertEquals(24f, sprite.layoutHeight)
    }

    @Test
    fun `stretch ranges decode as a list of from-to pairs`() {
        val sprite = sprite(
            """{"width":20,"height":20,"x":0,"y":0,"stretchX":[[2,4],[10,14]],"stretchY":[[5,7]]}"""
        )
        assertEquals(listOf(listOf(2.0, 4.0), listOf(10.0, 14.0)), sprite.stretchX)
        assertEquals(listOf(listOf(5.0, 7.0)), sprite.stretchY)
    }

    @Test
    fun `the content box and text-fit hints decode`() {
        val sprite = sprite(
            """{"width":20,"height":20,"x":0,"y":0,"content":[2,2,18,18],""" +
                """"textFitWidth":"stretchOnly","textFitHeight":"proportional"}"""
        )
        assertEquals(listOf(2.0, 2.0, 18.0, 18.0), sprite.content)
        assertEquals("stretchOnly", sprite.textFitWidth)
        assertEquals("proportional", sprite.textFitHeight)
    }

    @Test
    fun `sdf defaults to false`() {
        assertEquals(false, sprite("""{"width":8,"height":8,"x":0,"y":0}""").sdf)
        assertEquals(true, sprite("""{"width":8,"height":8,"x":0,"y":0,"sdf":true}""").sdf)
    }
}
