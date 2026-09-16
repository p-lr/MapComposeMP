package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.sprites
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a sheet's id and an entry's name make the id a style addresses a sprite by.
 *
 * Pins the port of `_getSpriteImageId` (`src/render/image_manager.ts`) and of
 * `coerceSpriteToArray` (`src/util/style.ts`). Both are pure, so this stays in `commonTest`: no
 * `ImageBitmap` is allocated and androidHostTest runs it too.
 */
class SpriteIdTest {

    @Test
    fun `the default sheet keeps its entries bare`() {
        // Upstream: `spriteId === 'default' ? imageId : ...`. Namespacing it is what made every
        // bare `icon-image` in a style written as [{"id": "default", ...}] resolve to nothing.
        assertEquals("marker", SpriteManager.spriteImageId("default", "marker"))
    }

    @Test
    fun `a sheet with no id keeps its entries bare`() {
        // What a list entry with no `id` decodes to here.
        assertEquals("marker", SpriteManager.spriteImageId("", "marker"))
    }

    @Test
    fun `every other sheet namespaces its entries`() {
        assertEquals("overlay:marker", SpriteManager.spriteImageId("overlay", "marker"))
    }

    @Test
    fun `the single URL sprite form is the default sheet`() {
        val style = json.decodeFromString<MapLibreStyle>(
            """{"version":8,"sprite":"https://example.com/sprite","sources":{},"layers":[]}"""
        )
        val sprites = style.sprites
        assertEquals(1, sprites.size)
        assertEquals(SpriteManager.DEFAULT_SPRITE_ID, sprites[0].id)
        assertEquals("https://example.com/sprite", sprites[0].url)
    }

    @Test
    fun `the list sprite form keeps every declared id`() {
        val style = json.decodeFromString<MapLibreStyle>(
            """{"version":8,"sprite":[
                {"id":"default","url":"https://example.com/sprite"},
                {"id":"overlay","url":"https://example.com/overlay"}
            ],"sources":{},"layers":[]}"""
        )
        assertEquals(listOf("default", "overlay"), style.sprites.map { it.id })
        assertEquals(
            listOf("https://example.com/sprite", "https://example.com/overlay"),
            style.sprites.map { it.url }
        )
    }
}
