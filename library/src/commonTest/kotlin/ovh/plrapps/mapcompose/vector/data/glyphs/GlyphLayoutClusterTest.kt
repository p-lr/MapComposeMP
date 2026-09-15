package ovh.plrapps.mapcompose.vector.data.glyphs

import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shaping a label whose glyphs come from a `font-faces` file, which is drawn a grapheme cluster at a
 * time rather than a codepoint at a time.
 *
 * The fake font advances a codepoint glyph by [ADVANCE] and a cluster glyph by [CLUSTER_ADVANCE], so
 * which path a label took can be read straight off its width.
 */
class GlyphLayoutClusterTest {

    private companion object {
        const val ADVANCE = 12
        const val CLUSTER_ADVANCE = 20
        const val FONT_SIZE = 24f
        val STACK = listOf("Test Regular")

        /** "e" + COMBINING ACUTE ACCENT: one unit of writing, two codepoints. */
        val ACCENTED = "e" + Char(0x0301)
    }

    private fun glyph(id: Int, advance: Int) = Glyph(
        id = id,
        bitmap = ByteArray((10 + 2 * GLYPH_BORDER) * (12 + 2 * GLYPH_BORDER)) { 0xFF.toByte() },
        width = 10,
        height = 12,
        left = 1,
        top = -10,
        advance = advance,
    )

    private val codePoints: (List<String>, Int) -> Glyph? = { _, code -> glyph(code, ADVANCE) }

    private fun shape(
        text: String,
        clusters: ((List<String>, String) -> Glyph?)?,
        maxWidth: Float = 0f,
    ): ShapedLabel = GlyphLayout.shape(
        sections = listOf(TextSection(text)),
        glyphs = codePoints,
        defaultFontStack = STACK,
        clusters = clusters,
        fontSize = FONT_SIZE,
        letterSpacing = 0f,
        lineHeight = 1.2f,
        maxWidth = maxWidth,
        justify = TEXT_JUSTIFY_CENTER,
        vertical = false,
        transform = TEXT_TRANSFORM_NONE,
    )

    /** Draws only what it was given, which stands in for a font file's coverage. */
    private fun drawing(vararg drawn: String): (List<String>, String) -> Glyph? = { _, cluster ->
        if (cluster in drawn) glyph(cluster[0].code, CLUSTER_ADVANCE) else null
    }

    @Test
    fun `a cluster a font file draws is one item`() {
        val shaped = shape(ACCENTED, clusters = drawing(ACCENTED))
        assertEquals(1, shaped.glyphs.size)
        assertEquals(CLUSTER_ADVANCE * FONT_SIZE / ONE_EM, shaped.glyphs.single().advance)
    }

    @Test
    fun `a cluster no file draws falls back to its codepoints`() {
        val shaped = shape(ACCENTED, clusters = drawing())
        assertEquals(2, shaped.glyphs.size)
        assertTrue(shaped.glyphs.all { it.advance == ADVANCE * FONT_SIZE / ONE_EM })
    }

    @Test
    fun `with no cluster lookup nothing is segmented`() {
        val withoutLookup = shape(ACCENTED, clusters = null)
        val withEmptyLookup = shape(ACCENTED, clusters = drawing())
        assertEquals(2, withoutLookup.glyphs.size)
        assertEquals(withEmptyLookup.width, withoutLookup.width)
    }

    @Test
    fun `a single codepoint a font file draws wins over the server`() {
        // Upstream's resolution order: a declared file is asked before the glyph range is.
        val shaped = shape("A", clusters = drawing("A"))
        assertEquals(1, shaped.glyphs.size)
        assertEquals(CLUSTER_ADVANCE * FONT_SIZE / ONE_EM, shaped.glyphs.single().advance)
    }

    @Test
    fun `a codepoint no file draws still comes from the server`() {
        val shaped = shape("AB", clusters = drawing("A"))
        assertEquals(2, shaped.glyphs.size)
        assertEquals(CLUSTER_ADVANCE * FONT_SIZE / ONE_EM, shaped.glyphs[0].advance)
        assertEquals(ADVANCE * FONT_SIZE / ONE_EM, shaped.glyphs[1].advance)
    }

    @Test
    fun `a line never breaks inside a cluster`() {
        val text = "$ACCENTED $ACCENTED"
        // Narrow enough that the two clusters cannot share a line.
        val shaped = shape(text, clusters = drawing(ACCENTED), maxWidth = 1f)
        assertEquals(2, shaped.lines.size)
        assertTrue(shaped.lines.all { it.glyphs.size == 1 })
    }

    @Test
    fun `the clusters of a label keep their order`() {
        val text = "a${ACCENTED}b"
        val shaped = shape(text, clusters = drawing(ACCENTED))
        val advances = shaped.glyphs.map { it.advance }
        assertEquals(
            listOf(
                ADVANCE * FONT_SIZE / ONE_EM,
                CLUSTER_ADVANCE * FONT_SIZE / ONE_EM,
                ADVANCE * FONT_SIZE / ONE_EM,
            ),
            advances,
        )
    }
}
