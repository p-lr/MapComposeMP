package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.fontStack
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyph
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyphsFile
import ovh.plrapps.mapcompose.vector.spec.style.FontFaceDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a style's `font-faces` files and its `glyphs` server divide a label between them.
 *
 * Upstream's order is kept: a declared file draws what it covers, whole grapheme clusters included,
 * and only what no file covers is asked of the server.
 */
class GlyphManagerFontFaceTest {

    private companion object {
        const val FONT_URL = "https://example.com/khmer.ttf"
        const val TEMPLATE = "https://example.com/{fontstack}/{range}.pbf"
        val STACK = listOf("Noto Sans Regular")

        /** "e" + COMBINING ACUTE ACCENT. */
        val ACCENTED = "e" + Char(0x0301)
    }

    private class Recorder(val answer: (String) -> ByteArray?) {
        val requested = mutableListOf<String>()
        suspend fun load(url: String): RawSource? {
            requested += url
            return answer(url)?.let { Buffer().apply { write(it) } }
        }
    }

    /** Stands in for the platform text stack, and records what it was asked to draw. */
    private class FakeRasterizer(private val draws: (String) -> Boolean = { true }) : LocalGlyphSource {
        val drawn = mutableListOf<String>()
        override suspend fun rasterize(family: FontFamily, grapheme: String): Glyph? {
            drawn += grapheme
            if (!draws(grapheme)) return null
            return Glyph(
                id = grapheme[0].code,
                bitmap = ByteArray((4 + 2 * GLYPH_BORDER) * (4 + 2 * GLYPH_BORDER)),
                width = 4,
                height = 4,
                left = 0,
                top = -4,
                advance = 9,
            )
        }
    }

    private fun rangeBytes(vararg ids: Int): ByteArray = glyphsFile(
        fontStack("stack", "0-255", ids.map { glyph(it, 4, 4, 0, 4, 6) })
    )

    private fun manager(
        recorder: Recorder,
        rasterizer: LocalGlyphSource?,
        urlTemplate: String? = TEMPLATE,
        vararg ranges: String,
    ): GlyphManager {
        val faces = FontFaceManager(
            listOf(FontFaceDeclaration("Noto Sans Regular", FONT_URL, ranges.toList())),
            recorder::load,
            { _, _ -> FontFamily.Default },
        )
        return GlyphManager(
            urlTemplate = urlTemplate,
            loadResource = recorder::load,
            fontFaces = faces,
            localGlyphs = rasterizer,
        )
    }

    @Test
    fun `a codepoint a declared file covers is drawn and never asked of the server`() = runTest {
        val recorder = Recorder { ByteArray(8) }
        val rasterizer = FakeRasterizer()
        val glyphs = manager(recorder, rasterizer).glyphsFor(STACK, "A")

        assertEquals(listOf("A"), rasterizer.drawn)
        assertEquals(setOf("A"), glyphs.byGrapheme.keys)
        assertTrue(glyphs.byCodePoint.isEmpty())
        assertEquals(listOf(FONT_URL), recorder.requested)
    }

    @Test
    fun `a codepoint outside every declared range still comes from the server`() = runTest {
        val recorder = Recorder { url -> if (url == FONT_URL) ByteArray(8) else rangeBytes(65) }
        val rasterizer = FakeRasterizer()
        val glyphs = manager(recorder, rasterizer, ranges = arrayOf("U+1780-17FF")).glyphsFor(STACK, "A")

        assertTrue(rasterizer.drawn.isEmpty())
        assertEquals(setOf(65), glyphs.byCodePoint.keys)
        assertEquals(listOf("https://example.com/Noto%20Sans%20Regular/0-255.pbf"), recorder.requested)
    }

    @Test
    fun `a grapheme cluster is drawn whole`() = runTest {
        val recorder = Recorder { ByteArray(8) }
        val rasterizer = FakeRasterizer()
        val glyphs = manager(recorder, rasterizer).glyphsFor(STACK, ACCENTED)

        assertEquals(listOf(ACCENTED), rasterizer.drawn)
        assertEquals(setOf(ACCENTED), glyphs.byGrapheme.keys)
    }

    @Test
    fun `a cluster no file covers is left to the server codepoint by codepoint`() = runTest {
        // A divergence, and a deliberate one: upstream draws nothing for such a cluster, which would
        // mean adding a font file for one script blanks the accented words of another.
        val recorder = Recorder { url -> if (url == FONT_URL) ByteArray(8) else rangeBytes(0x65, 0x301) }
        val rasterizer = FakeRasterizer()
        val glyphs = manager(recorder, rasterizer, ranges = arrayOf("U+1780-17FF"))
            .glyphsFor(STACK, ACCENTED)

        assertTrue(rasterizer.drawn.isEmpty())
        assertEquals(setOf(0x65, 0x301), glyphs.byCodePoint.keys)
    }

    @Test
    fun `a file that covers a codepoint but cannot draw it leaves it to the server`() = runTest {
        val recorder = Recorder { url -> if (url == FONT_URL) ByteArray(8) else rangeBytes(65) }
        val rasterizer = FakeRasterizer(draws = { false })
        val glyphs = manager(recorder, rasterizer).glyphsFor(STACK, "A")

        assertEquals(listOf("A"), rasterizer.drawn)
        assertTrue(glyphs.byGrapheme.isEmpty())
        assertEquals(setOf(65), glyphs.byCodePoint.keys)
    }

    @Test
    fun `a grapheme the file cannot draw is not attempted again`() = runTest {
        val recorder = Recorder { url -> if (url == FONT_URL) ByteArray(8) else rangeBytes(65) }
        val rasterizer = FakeRasterizer(draws = { false })
        val glyphManager = manager(recorder, rasterizer)
        glyphManager.glyphsFor(STACK, "A")
        glyphManager.glyphsFor(STACK, "A")

        assertEquals(listOf("A"), rasterizer.drawn)
    }

    @Test
    fun `a drawn grapheme is kept rather than drawn again`() = runTest {
        val recorder = Recorder { ByteArray(8) }
        val rasterizer = FakeRasterizer()
        val glyphManager = manager(recorder, rasterizer)
        glyphManager.glyphsFor(STACK, "AA")
        glyphManager.glyphsFor(STACK, "A")

        assertEquals(listOf("A"), rasterizer.drawn)
    }

    @Test
    fun `a style with font files and no glyph server still has glyphs`() = runTest {
        val recorder = Recorder { ByteArray(8) }
        val rasterizer = FakeRasterizer()
        val glyphManager = manager(recorder, rasterizer, urlTemplate = null)

        assertTrue(glyphManager.isConfigured)
        assertEquals(setOf("A"), glyphManager.glyphsFor(STACK, "A").byGrapheme.keys)
    }

    @Test
    fun `font faces with nothing to draw with fall back to the server`() = runTest {
        // No rasterizer -- a decode outside a composition -- so the files cannot be drawn from.
        val recorder = Recorder { rangeBytes(65) }
        val glyphs = manager(recorder, rasterizer = null).glyphsFor(STACK, "A")

        assertEquals(setOf(65), glyphs.byCodePoint.keys)
        assertEquals(listOf("https://example.com/Noto%20Sans%20Regular/0-255.pbf"), recorder.requested)
    }

    @Test
    fun `a label mixing covered and uncovered text splits between the two`() = runTest {
        val recorder = Recorder { url -> if (url == FONT_URL) ByteArray(8) else rangeBytes(65) }
        val rasterizer = FakeRasterizer()
        val glyphs = manager(recorder, rasterizer, ranges = arrayOf("U+1780-17FF"))
            .glyphsFor(STACK, "A" + Char(0x1780))

        assertEquals(listOf(Char(0x1780).toString()), rasterizer.drawn)
        assertEquals(setOf(65), glyphs.byCodePoint.keys)
    }
}
