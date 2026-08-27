package ovh.plrapps.mapcompose.vector.data.glyphs

import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.bytesField
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.fontStack
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyph
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.glyphsFile
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.solidBitmap
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphPbfFixtures.varintField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand-rolled reader for MapLibre's `glyphs.proto`. */
class GlyphPbfTest {

    @Test
    fun `a range decodes its stack and its glyphs`() {
        val bytes = glyphsFile(
            fontStack(
                name = "Open Sans Regular",
                range = "0-255",
                glyphs = listOf(
                    glyph(id = 65, width = 10, height = 12, left = 1, top = 11, advance = 13),
                    glyph(id = 66, width = 9, height = 12, left = 2, top = 11, advance = 14),
                ),
            )
        )

        val stacks = GlyphPbf.decode(bytes)
        assertEquals(1, stacks.size)
        assertEquals("Open Sans Regular", stacks[0].fontStack)
        assertEquals("0-255", stacks[0].range)
        assertEquals(setOf(65, 66), stacks[0].glyphs.keys)

        val a = assertNotNull(stacks[0].glyphs[65])
        assertEquals(10, a.width)
        assertEquals(12, a.height)
        assertEquals(1, a.left)
        assertEquals(11, a.top)
        assertEquals(13, a.advance)
    }

    @Test
    fun `a negative left is zigzag encoded`() {
        val bytes = glyphsFile(
            fontStack("f", "0-255", listOf(glyph(id = 1, width = 4, height = 4, left = -3, top = -2, advance = 5)))
        )
        val decoded = assertNotNull(GlyphPbf.decode(bytes).single().glyphs[1])
        assertEquals(-3, decoded.left)
        assertEquals(-2, decoded.top)
    }

    @Test
    fun `a glyph without a bitmap has no ink`() {
        val bytes = glyphsFile(
            fontStack("f", "0-255", listOf(glyph(id = 32, width = 0, height = 0, left = 0, top = 0, advance = 6)))
        )
        val space = assertNotNull(GlyphPbf.decode(bytes).single().glyphs[32])
        assertEquals(false, space.hasBitmap)
        assertEquals(6, space.advance)
        assertNull(space.bitmap)
    }

    @Test
    fun `a bitmap keeps its border ring`() {
        val bytes = glyphsFile(
            fontStack(
                "f", "0-255",
                listOf(
                    glyph(
                        id = 65, width = 4, height = 4, left = 0, top = 4, advance = 5,
                        bitmap = solidBitmap(4, 4),
                    )
                ),
            )
        )
        val decoded = assertNotNull(GlyphPbf.decode(bytes).single().glyphs[65])
        assertEquals(4 + 2 * GLYPH_BORDER, decoded.bitmapWidth)
        assertEquals(4 + 2 * GLYPH_BORDER, decoded.bitmapHeight)
        assertEquals(1f, decoded.distanceAt(0, 0))
        // Outside the bitmap is "infinitely far away", not an exception.
        assertEquals(0f, decoded.distanceAt(-1, 0))
        assertEquals(0f, decoded.distanceAt(decoded.bitmapWidth, 0))
    }

    @Test
    fun `several stacks in one file all decode`() {
        val bytes = glyphsFile(
            fontStack("a", "0-255", listOf(glyph(1, 4, 4, 0, 4, 5))),
            fontStack("b", "0-255", listOf(glyph(2, 4, 4, 0, 4, 5))),
        )
        assertEquals(listOf("a", "b"), GlyphPbf.decode(bytes).map { it.fontStack })
    }

    @Test
    fun `an unknown field is skipped rather than rejected`() {
        val extended = fontStack("f", "0-255", listOf(glyph(65, 4, 4, 0, 4, 5))) +
            varintField(99, 7L) +
            bytesField(98, "future".encodeToByteArray())
        val stacks = GlyphPbf.decode(glyphsFile(extended))
        assertEquals("f", stacks.single().fontStack)
        assertEquals(setOf(65), stacks.single().glyphs.keys)
    }

    @Test
    fun `bytes that are not a glyph range decode to nothing`() {
        // A 404 page, a truncated response: the label loses its glyphs, the tile survives.
        assertTrue(GlyphPbf.decode("not a protobuf at all".encodeToByteArray()).isEmpty() ||
            GlyphPbf.decode("not a protobuf at all".encodeToByteArray()).all { it.glyphs.isEmpty() })
        assertTrue(GlyphPbf.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `a truncated message does not throw`() {
        val full = glyphsFile(fontStack("f", "0-255", listOf(glyph(65, 4, 4, 0, 4, 5))))
        assertTrue(GlyphPbf.decode(full.copyOfRange(0, full.size / 2)).isEmpty())
    }
}
