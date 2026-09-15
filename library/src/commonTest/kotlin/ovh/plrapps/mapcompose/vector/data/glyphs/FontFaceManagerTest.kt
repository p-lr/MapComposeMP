package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import androidx.compose.ui.text.font.FontFamily
import ovh.plrapps.mapcompose.vector.spec.style.FontFaceDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which declared font file draws a codepoint, and how often it is asked for. */
class FontFaceManagerTest {

    private class Recorder(val answer: (String) -> ByteArray? = { FONT_BYTES }) {
        val requested = mutableListOf<String>()
        suspend fun load(url: String): RawSource? {
            requested += url
            return answer(url)?.let { Buffer().apply { write(it) } }
        }
    }

    /** Stands in for the platform font stack: every file "loads", none is actually parsed. */
    private val buildFamily: (String, ByteArray) -> FontFamily? = { _, _ -> FontFamily.Default }

    private fun declaration(
        fontName: String,
        url: String,
        vararg unicodeRange: String,
    ) = FontFaceDeclaration(fontName, url, unicodeRange.toList())

    @Test
    fun `nothing is fetched until a codepoint needs a file`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf")),
            recorder::load,
            buildFamily,
        )
        assertTrue(manager.hasFontFaces)
        assertTrue(recorder.requested.isEmpty())

        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
        assertEquals(listOf("https://example.com/noto.ttf"), recorder.requested)
    }

    @Test
    fun `a file is downloaded once however many codepoints it draws`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf")),
            recorder::load,
            buildFamily,
        )
        manager.familyFor(listOf("Noto Sans Regular"), 'A'.code)
        manager.familyFor(listOf("Noto Sans Regular"), 'B'.code)
        assertEquals(1, recorder.requested.size)
    }

    @Test
    fun `a declaration with no unicode range covers every codepoint`() = runTest {
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf")),
            Recorder()::load,
            buildFamily,
        )
        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 0x1780))
    }

    @Test
    fun `a file is skipped where its unicode range does not reach`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/khmer.ttf", "U+1780-17FF")),
            recorder::load,
            buildFamily,
        )
        assertNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
        assertTrue(recorder.requested.isEmpty())
        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 0x1780))
        assertEquals(listOf("https://example.com/khmer.ttf"), recorder.requested)
    }

    @Test
    fun `the files of one name are tried in declaration order`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(
                declaration("Noto Sans Regular", "https://example.com/khmer.ttf", "U+1780-17FF"),
                declaration("Noto Sans Regular", "https://example.com/latin.ttf", "U+0-2FF"),
            ),
            recorder::load,
            buildFamily,
        )
        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
        assertEquals(listOf("https://example.com/latin.ttf"), recorder.requested)
    }

    @Test
    fun `the font stack is walked in order`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(
                declaration("Second", "https://example.com/second.ttf"),
                declaration("First", "https://example.com/first.ttf"),
            ),
            recorder::load,
            buildFamily,
        )
        assertNotNull(manager.familyFor(listOf("First", "Second"), 'A'.code))
        assertEquals(listOf("https://example.com/first.ttf"), recorder.requested)
    }

    @Test
    fun `a name the style declared nothing for reaches no file`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf")),
            recorder::load,
            buildFamily,
        )
        assertNull(manager.familyFor(listOf("Open Sans Regular"), 'A'.code))
        assertTrue(recorder.requested.isEmpty())
    }

    @Test
    fun `a file that fails to download costs one request and then the next file draws`() = runTest {
        val recorder = Recorder { url -> if (url.endsWith("broken.ttf")) null else FONT_BYTES }
        val manager = FontFaceManager(
            listOf(
                declaration("Noto Sans Regular", "https://example.com/broken.ttf"),
                declaration("Noto Sans Regular", "https://example.com/noto.ttf"),
            ),
            recorder::load,
            buildFamily,
        )
        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
        assertNotNull(manager.familyFor(listOf("Noto Sans Regular"), 'B'.code))
        assertEquals(
            listOf("https://example.com/broken.ttf", "https://example.com/noto.ttf"),
            recorder.requested,
        )
    }

    @Test
    fun `an empty response is a failure rather than an empty font`() = runTest {
        val recorder = Recorder { ByteArray(0) }
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf")),
            recorder::load,
            buildFamily,
        )
        assertNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
    }

    @Test
    fun `a declaration whose every range is malformed is dropped`() = runTest {
        val recorder = Recorder()
        val manager = FontFaceManager(
            listOf(declaration("Noto Sans Regular", "https://example.com/noto.ttf", "nonsense")),
            recorder::load,
            buildFamily,
        )
        assertTrue(!manager.hasFontFaces)
        assertNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
        assertTrue(recorder.requested.isEmpty())
    }

    @Test
    fun `a style declaring no file draws nothing locally`() = runTest {
        val manager = FontFaceManager(emptyList(), Recorder()::load, buildFamily)
        assertTrue(!manager.hasFontFaces)
        assertNull(manager.familyFor(listOf("Noto Sans Regular"), 'A'.code))
    }

    private companion object {
        /** Stands in for a font file; nothing here parses it. */
        val FONT_BYTES = ByteArray(16) { it.toByte() }
    }
}
