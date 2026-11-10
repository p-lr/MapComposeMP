package ovh.plrapps.mapcompose.vector.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a sheet's URL grows its `@2x` suffix and its extension.
 *
 * Pins the port of `style/load_sprite.ts`'s `parsed.pathname += `${format}${extension}``: the
 * suffix and extension belong to the **path**, so a query string or a fragment stays behind them.
 * Pure string work, so this stays in `commonTest`.
 */
class SpriteUrlTest {

    @Test
    fun `a plain url takes the suffix and extension`() {
        assertEquals(
            "https://host/sprite.json",
            SpriteManager.spriteUrlWith("https://host/sprite", "", ".json")
        )
        assertEquals(
            "https://host/sprite@2x.png",
            SpriteManager.spriteUrlWith("https://host/sprite", "@2x", ".png")
        )
    }

    @Test
    fun `a query string stays after the extension`() {
        // `sprite?token=abc.json` is a 404 on every authenticated sprite endpoint.
        assertEquals(
            "https://host/sprite.json?token=abc",
            SpriteManager.spriteUrlWith("https://host/sprite?token=abc", "", ".json")
        )
        assertEquals(
            "https://host/sprite@2x.png?token=abc",
            SpriteManager.spriteUrlWith("https://host/sprite?token=abc", "@2x", ".png")
        )
    }

    @Test
    fun `a fragment stays after the extension`() {
        assertEquals(
            "https://host/sprite.json#frag",
            SpriteManager.spriteUrlWith("https://host/sprite#frag", "", ".json")
        )
    }

    @Test
    fun `a query and a fragment are kept together`() {
        assertEquals(
            "https://host/sprite@2x.json?a=1#frag",
            SpriteManager.spriteUrlWith("https://host/sprite?a=1#frag", "@2x", ".json")
        )
    }

    @Test
    fun `a fragment before a question mark is still the boundary`() {
        // Whatever the scheme, everything from the first `?` or `#` is no longer the path.
        assertEquals(
            "https://host/sprite.json#a?b",
            SpriteManager.spriteUrlWith("https://host/sprite#a?b", "", ".json")
        )
    }
}
