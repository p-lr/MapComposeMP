package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.fontStack
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyph
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyphsFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Range arithmetic, URL templating and caching around the glyph server. */
class GlyphManagerTest {

    private fun source(bytes: ByteArray): RawSource = Buffer().apply { write(bytes) }

    private class Recorder(val answer: (String) -> ByteArray?) {
        val requested = mutableListOf<String>()
        suspend fun load(url: String): RawSource? {
            requested += url
            return answer(url)?.let { Buffer().apply { write(it) } }
        }
    }

    private fun rangeBytes(vararg ids: Int): ByteArray = glyphsFile(
        fontStack("stack", "0-255", ids.map { glyph(it, 4, 4, 0, 4, 6) })
    )

    @Test
    fun `ascii needs only the first range`() {
        assertEquals(listOf(0), GlyphManager.rangesOf("Hello"))
    }

    @Test
    fun `each 256 codepoints is its own range`() {
        assertEquals(listOf(0, 1), GlyphManager.rangesOf("AĀ"))
        assertEquals(listOf(78), GlyphManager.rangesOf("中"))
    }

    @Test
    fun `a range is listed once however often it is used`() {
        assertEquals(listOf(0), GlyphManager.rangesOf("aaaaabbbbb"))
    }

    @Test
    fun `codepoints above the basic multilingual plane are skipped`() {
        // Glyph servers publish 0-255 through 65280-65535 and nothing above; an emoji would only
        // produce a guaranteed 404.
        assertEquals(emptyList(), GlyphManager.rangesOf("😀"))
        // ...and its surrogates must not be read as two separate codepoints either.
        assertEquals(listOf(0), GlyphManager.rangesOf("a😀"))
    }

    @Test
    fun `a font stack is percent-encoded for the url`() {
        assertEquals("Open%20Sans%20Regular", GlyphManager.encodeFontStack("Open Sans Regular"))
        // The comma joining a stack stays a comma, as the template's {fontstack} expects.
        assertEquals("A%20B,C%20D", GlyphManager.encodeFontStack("A B,C D"))
    }

    @Test
    fun `the url template is filled with the stack and the range bounds`() = runTest {
        val recorder = Recorder { rangeBytes(65) }
        val manager = GlyphManager(
            urlTemplate = "https://example.com/fonts/{fontstack}/{range}.pbf",
            loadResource = recorder::load,
        )
        manager.glyphsFor(listOf("Open Sans Regular", "Arial Unicode MS Regular"), "A")
        assertEquals(
            listOf(
                "https://example.com/fonts/Open%20Sans%20Regular,Arial%20Unicode%20MS%20Regular/0-255.pbf"
            ),
            recorder.requested,
        )
    }

    @Test
    fun `a range is fetched once and then cached`() = runTest {
        val recorder = Recorder { rangeBytes(65, 66) }
        val manager = GlyphManager("https://example.com/{fontstack}/{range}.pbf", recorder::load)
        manager.glyphsFor(listOf("f"), "AB")
        manager.glyphsFor(listOf("f"), "BA")
        assertEquals(1, recorder.requested.size)
    }

    @Test
    fun `a failed range is cached as empty rather than retried forever`() = runTest {
        val recorder = Recorder { null }
        val manager = GlyphManager("https://example.com/{fontstack}/{range}.pbf", recorder::load)
        assertTrue(manager.glyphsFor(listOf("f"), "A").isEmpty)
        assertTrue(manager.glyphsFor(listOf("f"), "A").isEmpty)
        assertEquals(1, recorder.requested.size)
    }

    @Test
    fun `a style without a glyphs url asks for nothing`() = runTest {
        val recorder = Recorder { rangeBytes(65) }
        val manager = GlyphManager(urlTemplate = null, loadResource = recorder::load)
        assertEquals(false, manager.isConfigured)
        assertTrue(manager.glyphsFor(listOf("f"), "A").isEmpty)
        assertTrue(recorder.requested.isEmpty())
    }

    @Test
    fun `the decoded glyphs come back keyed by codepoint`() = runTest {
        val recorder = Recorder { rangeBytes(65, 66) }
        val manager = GlyphManager("https://example.com/{fontstack}/{range}.pbf", recorder::load)
        val glyphs = manager.glyphsFor(listOf("f"), "AB").byCodePoint
        assertEquals(setOf(65, 66), glyphs.keys)
        assertEquals(6, glyphs.getValue(65).advance)
    }

    @Test
    fun `text spanning two ranges fetches both`() = runTest {
        val recorder = Recorder { rangeBytes(65) }
        val manager = GlyphManager("https://example.com/{fontstack}/{range}.pbf", recorder::load)
        manager.glyphsFor(listOf("f"), "A中")
        assertEquals(
            listOf("https://example.com/f/0-255.pbf", "https://example.com/f/19968-20223.pbf"),
            recorder.requested,
        )
    }
}
