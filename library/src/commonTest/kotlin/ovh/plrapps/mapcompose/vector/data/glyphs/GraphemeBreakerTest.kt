package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grapheme segmentation, as much of it as every target agrees on.
 *
 * `java.text.BreakIterator`, Foundation's composed character sequences and `Intl.Segmenter` are
 * three different answers to the same question and part ways at the edges -- an emoji ZWJ sequence
 * is one grapheme to two of them and several to the third -- so only a letter with its combining
 * marks is asserted here, which is the case the `font-faces` path exists for. The same rule
 * `CollatorTest` follows.
 */
class GraphemeBreakerTest {

    @Test
    fun `plain text is one grapheme per character`() {
        assertEquals(listOf("a", "b", "c"), graphemeClusters("abc"))
    }

    @Test
    fun `a letter keeps its combining mark`() {
        // "e" + COMBINING ACUTE ACCENT, which is one unit of writing and two codepoints.
        val text = "e" + Char(0x0301)
        assertEquals(listOf(text), graphemeClusters(text))
    }

    @Test
    fun `a mark only joins the letter before it`() {
        val text = "a" + "e" + Char(0x0301) + "b"
        assertEquals(listOf("a", "e" + Char(0x0301), "b"), graphemeClusters(text))
    }

    @Test
    fun `text with no marks segments into its codepoints`() {
        val text = "Zürich"
        assertEquals(text.length, graphemeClusters(text).size)
        assertEquals(text, graphemeClusters(text).joinToString(""))
    }

    @Test
    fun `the segments always rebuild the text`() {
        val text = "aé" + Char(0x0301) + " 中文"
        assertEquals(text, graphemeClusters(text).joinToString(""))
    }

    @Test
    fun `empty text has no graphemes`() {
        assertTrue(graphemeClusters("").isEmpty())
    }

    @Test
    fun `a codepoint above the basic plane stays whole`() {
        // Its two surrogates are one codepoint and so cannot be two graphemes.
        val text = "a😀b"
        val clusters = graphemeClusters(text)
        assertEquals(3, clusters.size)
        assertEquals(text, clusters.joinToString(""))
    }

    @Test
    fun `the codepoint fallback splits on codepoints and not on code units`() {
        val text = "a😀" + "e" + Char(0x0301)
        assertEquals(listOf("a", "😀", "e", Char(0x0301).toString()), codePointStrings(text))
    }
}
