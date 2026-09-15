package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drawing a grapheme with the platform's text stack, which is what a `font-faces` file is drawn
 * through.
 *
 * It runs against [FontFamily.Default] rather than a downloaded file: what is under test is the
 * arithmetic between a laid-out run and a [Glyph] -- the ink box, the distance field's size, the
 * metrics' scale -- and none of that depends on which font drew the ink. A real file only changes
 * the shapes. It lives in `skiaTest` for the reason every painter test does: `ImageBitmap` needs a
 * graphics backend, which androidHostTest has none of.
 */
class LocalGlyphRasterizerTest {

    private fun rasterizer(): ComposeGlyphRasterizer {
        val measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
        return ComposeGlyphRasterizer(MutableStateFlow(measurer))
    }

    private suspend fun draw(text: String): Glyph? =
        rasterizer().rasterize(FontFamily.Default, text)

    @Test
    fun `a letter comes back with ink and an advance`() = runTest {
        val glyph = assertNotNull(draw("A"))
        assertTrue(glyph.hasBitmap, "a letter should carry a distance field")
        assertTrue(glyph.width > 0 && glyph.height > 0)
        assertTrue(glyph.advance > 0, "a letter should move the pen")
    }

    @Test
    fun `the distance field is the ink box grown by the glyph border`() = runTest {
        val glyph = assertNotNull(draw("A"))
        assertEquals(
            glyph.bitmapWidth * glyph.bitmapHeight,
            glyph.bitmap?.size,
            "the field has to be exactly the size every reader assumes",
        )
        assertEquals(glyph.width + 2 * GLYPH_BORDER, glyph.bitmapWidth)
        assertEquals(glyph.height + 2 * GLYPH_BORDER, glyph.bitmapHeight)
    }

    @Test
    fun `the metrics are in glyph units and not in the pixels it was drawn at`() = runTest {
        val glyph = assertNotNull(draw("A"))
        // One em is ONE_EM units, so a capital letter is a fraction of that, never 48 pixels of one.
        assertTrue(glyph.height in 1..ONE_EM, "height ${glyph.height} should be within one em")
        assertTrue(glyph.advance in 1..(2 * ONE_EM), "advance ${glyph.advance} should be about an em")
    }

    @Test
    fun `the pen sits above the baseline as a server glyph's does`() = runTest {
        val glyph = assertNotNull(draw("A"))
        /* `top` is negative-upward from the pen and the pen is 27.5 units above the baseline, so a
         * capital letter -- whose ink is well under that -- reads negative. A positive value would
         * be the sign error that drops every label a whole line. */
        assertTrue(glyph.top < 0, "top ${glyph.top} should place the ink below the pen")
        assertTrue(glyph.top > -ONE_EM, "top ${glyph.top} should not be a whole em away")
    }

    @Test
    fun `a space has metrics and no ink`() = runTest {
        val glyph = assertNotNull(draw(" "))
        assertTrue(!glyph.hasBitmap, "a space has nothing to draw")
        assertTrue(glyph.advance > 0, "a space still moves the pen")
    }

    @Test
    fun `a wider letter advances further`() = runTest {
        val narrow = assertNotNull(draw("i"))
        val wide = assertNotNull(draw("W"))
        assertTrue(wide.advance >= narrow.advance)
        assertTrue(wide.width > narrow.width)
    }

    @Test
    fun `the field is dark outside the ink and bright inside it`() = runTest {
        val glyph = assertNotNull(draw("H"))
        val corner = glyph.distanceAt(0, 0)
        val centre = glyph.distanceAt(glyph.bitmapWidth / 2, glyph.bitmapHeight / 2)
        assertTrue(corner < centre, "the corner $corner should read further out than the centre $centre")
    }

    @Test
    fun `a grapheme carries the codepoint it starts with`() = runTest {
        val glyph = assertNotNull(draw("e" + Char(0x0301)))
        assertEquals('e'.code, glyph.id)
    }

    @Test
    fun `nothing is drawn for empty text`() = runTest {
        assertNull(draw(""))
    }
}
