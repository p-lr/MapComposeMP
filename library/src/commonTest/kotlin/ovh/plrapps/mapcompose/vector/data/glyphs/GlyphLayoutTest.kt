package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_LEFT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_RIGHT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_LOWERCASE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_UPPERCASE
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_HORIZONTAL
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_VERTICAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shaping, against `maplibre-gl-js/src/symbol/shaping.ts`.
 *
 * A monospaced fake font keeps the arithmetic checkable by hand: every glyph advances 12 units, half
 * an em, so a 24 px label lays out at 12 px a character.
 */
class GlyphLayoutTest {

    private companion object {
        const val ADVANCE = 12
        const val FONT_SIZE = 24f
        val STACK = listOf("Test Regular")
    }

    /** Every codepoint exists, is square ink and advances half an em. A space has no ink. */
    private fun font(missing: Set<Int> = emptySet()): (List<String>, Int) -> Glyph? = { _, code ->
        when {
            code in missing -> null
            code == 0x20 -> Glyph(code, null, 0, 0, 0, 0, ADVANCE)
            else -> Glyph(
                id = code,
                bitmap = ByteArray((10 + 2 * GLYPH_BORDER) * (12 + 2 * GLYPH_BORDER)) { 0xFF.toByte() },
                width = 10, height = 12, left = 1, top = 12, advance = ADVANCE,
            )
        }
    }

    private fun shape(
        text: String,
        fontSize: Float = FONT_SIZE,
        letterSpacing: Float = 0f,
        lineHeight: Float = 1.2f,
        maxWidth: Float = 0f,
        justify: String = TEXT_JUSTIFY_CENTER,
        writingMode: List<String>? = null,
        transform: String = TEXT_TRANSFORM_NONE,
        missing: Set<Int> = emptySet(),
        sections: List<TextSection>? = null,
    ): ShapedLabel = GlyphLayout.shape(
        sections = sections ?: listOf(TextSection(text)),
        glyphs = font(missing),
        defaultFontStack = STACK,
        fontSize = fontSize,
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        maxWidth = maxWidth,
        justify = justify,
        writingMode = writingMode,
        transform = transform,
    )

    @Test
    fun `advances accumulate across a line`() {
        val label = shape("abc")
        // 3 glyphs at half an em, at a 24 px em.
        assertEquals(36f, label.width)
        assertEquals(3, label.glyphs.size)
        assertEquals(listOf(0f, 12f, 24f), label.glyphs.map { it.x })
    }

    @Test
    fun `a smaller text-size scales the whole line`() {
        assertEquals(18f, shape("abc", fontSize = 12f).width)
    }

    @Test
    fun `letter-spacing is in ems and is not added after the last glyph`() {
        // 0.5 em of extra spacing = 12 px at a 24 px em, between the three glyphs only.
        val label = shape("abc", letterSpacing = 0.5f)
        assertEquals(36f + 24f, label.width)
        assertEquals(listOf(0f, 24f, 48f), label.glyphs.map { it.x })
    }

    @Test
    fun `a glyph the font stack lacks contributes nothing`() {
        // Upstream's `shapeLines` skips a missing glyph outright -- it does not reserve its advance,
        // so the neighbours close up rather than leaving a gap.
        val label = shape("abc", missing = setOf('b'.code))
        assertEquals(2, label.glyphs.size)
        assertEquals(listOf(0f, 12f), label.glyphs.map { it.x })
        assertEquals(24f, label.width)
    }

    @Test
    fun `a space has no ink`() {
        val label = shape("a b")
        assertEquals(2, label.glyphs.size)
        assertEquals(36f, label.width)
    }

    @Test
    fun `text-max-width wraps at a space`() {
        // "aaa bbb" is 84 px wide; a 2 em limit is 48 px, so it becomes two lines.
        val label = shape("aaa bbb", maxWidth = 2f)
        assertEquals(2, label.lines.size)
        assertEquals(3, label.lines[0].glyphs.size)
        assertEquals(3, label.lines[1].glyphs.size)
    }

    @Test
    fun `wrapping balances the lines rather than filling greedily`() {
        // "aa bb cc" is 96 px at a 3 em (72 px) limit, so it wants two lines of about 48 px. A
        // greedy filler would put "aa bb" on the first line because it still fits under 72 and
        // leave "cc" as a stub; upstream breaks at the first space instead, which lands both lines
        // nearer the 48 px target.
        val label = shape("aa bb cc", maxWidth = 3f)
        assertEquals(2, label.lines.size)
        assertEquals(2, label.lines[0].glyphs.size)
        assertEquals(4, label.lines[1].glyphs.size)
    }

    @Test
    fun `a zero-width space is preferred over an ideographic break`() {
        // The +150 ideographic penalty applies only once the server has suggested breakpoints;
        // without one, CJK must still be free to wrap between characters.
        val label = shape("中中\u200b中中", maxWidth = 1f)
        assertEquals(2, label.lines.size)
        assertEquals(2, label.lines[0].glyphs.size)
    }

    @Test
    fun `text-max-width of zero never wraps`() {
        assertEquals(1, shape("aaaaaaaaaaaaaaaaaaaa", maxWidth = 0f).lines.size)
    }

    @Test
    fun `a newline is a near-free break`() {
        val label = shape("a\nb", maxWidth = 10f)
        assertEquals(2, label.lines.size)
    }

    @Test
    fun `text-line-height spaces the baselines`() {
        val tight = shape("aa bb", maxWidth = 1f, lineHeight = 1f)
        val loose = shape("aa bb", maxWidth = 1f, lineHeight = 2f)
        assertEquals(2, tight.lines.size)
        assertEquals(2, loose.lines.size)
        val tightGap = tight.lines[1].glyphs[0].y - tight.lines[0].glyphs[0].y
        val looseGap = loose.lines[1].glyphs[0].y - loose.lines[0].glyphs[0].y
        assertEquals(24f, tightGap)
        assertEquals(48f, looseGap)
        assertEquals(2 * 24f, tight.height)
        assertEquals(2 * 48f, loose.height)
    }

    @Test
    fun `text-justify left leaves the short line flush`() {
        val label = shape("aaa b", maxWidth = 2f, justify = TEXT_JUSTIFY_LEFT)
        assertEquals(2, label.lines.size)
        assertEquals(0f, label.lines[1].glyphs[0].x)
    }

    @Test
    fun `text-justify right pushes the short line over`() {
        val label = shape("aaa b", maxWidth = 2f, justify = TEXT_JUSTIFY_RIGHT)
        assertEquals(2, label.lines.size)
        assertEquals(label.width - 12f, label.lines[1].glyphs[0].x)
    }

    @Test
    fun `text-justify center splits the difference`() {
        val label = shape("aaa b", maxWidth = 2f, justify = TEXT_JUSTIFY_CENTER)
        assertEquals((label.width - 12f) / 2f, label.lines[1].glyphs[0].x)
    }

    @Test
    fun `text-transform uppercases before shaping`() {
        val label = shape("ab", transform = TEXT_TRANSFORM_UPPERCASE)
        assertEquals(listOf('A'.code, 'B'.code), label.glyphs.map { it.glyph.id })
    }

    @Test
    fun `text-transform lowercases before shaping`() {
        val label = shape("AB", transform = TEXT_TRANSFORM_LOWERCASE)
        assertEquals(listOf('a'.code, 'b'.code), label.glyphs.map { it.glyph.id })
    }

    @Test
    fun `text-writing-mode vertical stacks cjk glyphs`() {
        val label = shape("中中中", writingMode = listOf(WRITING_MODE_VERTICAL))
        assertTrue(label.vertical)
        assertEquals(3, label.lines.size)
        assertEquals(listOf(0f, 0f, 0f), label.glyphs.map { it.x })
        assertTrue(label.glyphs[1].y > label.glyphs[0].y)
    }

    @Test
    fun `latin text stays horizontal even when vertical is allowed`() {
        val label = shape("abc", writingMode = listOf(WRITING_MODE_VERTICAL, WRITING_MODE_HORIZONTAL))
        assertEquals(false, label.vertical)
        assertEquals(1, label.lines.size)
    }

    @Test
    fun `cjk wraps without a space`() {
        val label = shape("中中中中", maxWidth = 1f)
        assertTrue(label.lines.size > 1, "ideographs must break between characters")
    }

    @Test
    fun `a format section keeps its own scale and colour`() {
        val label = shape(
            text = "",
            sections = listOf(
                TextSection("a"),
                TextSection("b", scale = 2f, color = Color.Red),
            ),
        )
        assertEquals(2, label.glyphs.size)
        assertEquals(1f, label.glyphs[0].scale)
        assertEquals(2f, label.glyphs[1].scale)
        assertEquals(Color.Red, label.glyphs[1].color)
        // The scaled section advances twice as far.
        assertEquals(12f + 24f, label.width)
    }

    @Test
    fun `an empty label shapes to nothing`() {
        val label = shape("")
        assertTrue(label.isEmpty)
        assertEquals(0f, label.width)
    }
}
