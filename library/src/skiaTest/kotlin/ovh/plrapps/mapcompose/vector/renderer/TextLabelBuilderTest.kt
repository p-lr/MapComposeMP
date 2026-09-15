package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.FormattedSection
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `["format", ...]` with inline images, end to end.
 *
 * In `skiaTest` because a sprite is an `ImageBitmap`. The shaping this drives is covered without a
 * graphics backend by `GlyphLayoutTest`, and the compositing by `GlyphRasterizerTest`; what is left
 * here is the resolution against the sprite sheet and the cache key.
 */
class TextLabelBuilderTest {

    private companion object {
        const val ADVANCE = 12
        const val ASCENT = 22
        const val FONT_SIZE = 24f
        const val SPRITE_SIZE = 16
        val STACK = listOf("Test Regular")
    }

    private fun glyphManager(): GlyphManager {
        val bytes = GlyphPbfFixtures.glyphsFile(
            GlyphPbfFixtures.fontStack(
                name = STACK.single(),
                range = "0-255",
                glyphs = (33..126).map { code ->
                    GlyphPbfFixtures.glyph(
                        id = code, width = 10, height = 12, left = 1, top = 12 - ASCENT,
                        advance = ADVANCE, bitmap = GlyphPbfFixtures.solidBitmap(10, 12),
                    )
                },
            )
        )
        return GlyphManager(
            urlTemplate = "test://{fontstack}/{range}.pbf",
            loadResource = { _: String -> Buffer().apply { write(bytes) } as RawSource },
        )
    }

    private fun spriteManager(pixelRatio: Float = 1f, sdf: Boolean = false): SpriteManager =
        SpriteManager(
            spriteIndex = mapOf(
                "marker" to Sprite(
                    width = SPRITE_SIZE, height = SPRITE_SIZE, x = 0, y = 0,
                    pixelRatio = pixelRatio, sdf = sdf,
                )
            ),
            spriteImage = renderToBitmap(size = SPRITE_SIZE) { drawRect(color = Color.Red) },
        )

    private fun builder(
        glyphs: GlyphManager? = glyphManager(),
        sprites: SpriteManager? = spriteManager(),
        cache: LruCache<String, Any> = LruCache(maxSize = 64),
    ) = TextLabelBuilder(
        glyphManager = glyphs,
        spriteManager = sprites,
        textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
        cache = cache,
        mutex = Mutex(),
    )

    private fun style(color: Color = Color.Black) = ResolvedTextStyle(
        fontStack = STACK,
        fontSize = FONT_SIZE,
        color = color,
        opacity = 1f,
        haloColor = Color.Transparent,
        haloWidth = 0f,
        haloBlur = 0f,
        letterSpacing = 0f,
        lineHeight = 1.2f,
        maxWidth = 0f,
        justify = TEXT_JUSTIFY_CENTER,
        transform = TEXT_TRANSFORM_NONE,
        writingMode = null,
    )

    private fun image(name: String) =
        FormattedSection(text = "", image = ResolvedImage(name, available = true))

    @Test
    fun `a text-field made only of an image still renders`() = runTest {
        // It used to be rejected as blank: `Formatted.toString()` concatenates section *text*, and
        // an image section has none. Upstream's `TaggedString` gives it a private-use character.
        val art = assertNotNull(
            builder().build(Formatted(listOf(image("marker"))), style(), Density(1f))
        )
        assertTrue(art is LabelArt.Glyphs)
        assertEquals(SPRITE_SIZE.toFloat(), art.width)
        assertEquals(1, art.text.length)
    }

    @Test
    fun `an inline image advances the label beside its text`() = runTest {
        val art = assertNotNull(
            builder().build(
                Formatted(listOf(image("marker"), FormattedSection(text = "a"))),
                style(),
                Density(1f),
            )
        )
        assertEquals(SPRITE_SIZE + ADVANCE.toFloat(), art.width)
    }

    @Test
    fun `an image the sheet does not have is dropped`() = runTest {
        // Upstream's `if (!imagePosition) continue`: the section contributes no advance either.
        val art = assertNotNull(
            builder().build(
                Formatted(listOf(image("missing"), FormattedSection(text = "a"))),
                style(),
                Density(1f),
            )
        )
        assertEquals(ADVANCE.toFloat(), art.width)
    }

    @Test
    fun `an image scales with the density and out of its own pixel ratio`() = runTest {
        val art = assertNotNull(
            builder(sprites = spriteManager(pixelRatio = 2f))
                .build(Formatted(listOf(image("marker"))), style(), Density(2f))
        )
        // 16 sheet pixels at @2x is 8 style pixels, which is 16 device pixels at density 2.
        assertEquals(SPRITE_SIZE.toFloat(), art.width)
    }

    @Test
    fun `an sdf image is recoloured by the text's own paint`() = runTest {
        val art = assertNotNull(
            builder(sprites = spriteManager(sdf = true))
                .build(Formatted(listOf(image("marker"))), style(color = Color.Green), Density(1f))
        )
        assertTrue(art is LabelArt.Glyphs)
        val pixels = art.rendered.bitmap.toPixelMap()
        assertColorEquals(
            Color.Green,
            pixels[pixels.width / 2, pixels.height / 2],
            tolerance = 0.05f,
        )
    }

    @Test
    fun `an image only field needs no glyph server`() = runTest {
        val art = builder(glyphs = null).build(
            Formatted(listOf(image("marker"))), style(), Density(1f)
        )
        assertNotNull(art)
    }

    @Test
    fun `two labels differing only in a section colour are cached apart`() = runTest {
        // The key used to be the concatenated plain text, so these two shared one rasterization.
        val cache = LruCache<String, Any>(maxSize = 64)
        val builder = builder(cache = cache)
        val red = assertNotNull(
            builder.build(
                Formatted(listOf(FormattedSection(text = "a", textColor = Color.Red))),
                style(), Density(1f),
            )
        )
        val blue = assertNotNull(
            builder.build(
                Formatted(listOf(FormattedSection(text = "a", textColor = Color.Blue))),
                style(), Density(1f),
            )
        )
        assertTrue(red !== blue, "a section colour must reach the label cache key")
    }

    @Test
    fun `a blank text-field is still rejected`() = runTest {
        assertNull(builder().build(Formatted(listOf(FormattedSection(text = "   "))), style(), Density(1f)))
    }
}
