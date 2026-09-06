package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import ovh.plrapps.mapcompose.vector.data.glyphs.GLYPH_BORDER
import ovh.plrapps.mapcompose.vector.data.glyphs.Glyph
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphLayout
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphRasterizer
import ovh.plrapps.mapcompose.vector.data.glyphs.TextSection
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.renderToBitmap
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import ovh.plrapps.mapcompose.vector.symbol.LabelPath
import ovh.plrapps.mapcompose.vector.symbol.SymbolProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drawing a label along a bent path.
 *
 * In `skiaTest` because it rasterizes glyphs and draws them; the decisions that lead here -- when to
 * bend, which way round, at what angle -- are pure and live in `commonTest`'s `CurvedLabelTest`.
 */
class CurvedLabelDrawTest {

    private companion object {
        const val INK = 12
        const val ADVANCE = 24
        const val FONT_SIZE = 24f
        const val SIZE = 160
    }

    /** A solid square glyph: every pixel of its ink is unambiguous in the output. */
    private fun blockGlyph(): Glyph {
        val size = INK + 2 * GLYPH_BORDER
        val bitmap = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inset = minOf(x, y, size - 1 - x, size - 1 - y)
                val distance = (inset.toFloat() / GLYPH_BORDER).coerceIn(0f, 1f)
                bitmap[y * size + x] = (distance * 255f).toInt().toByte()
            }
        }
        return Glyph(id = 'a'.code, bitmap = bitmap, width = INK, height = INK, left = 0, top = 0, advance = ADVANCE)
    }

    private fun label(text: String): LabelArt.Glyphs {
        val glyph = blockGlyph()
        val shaped = GlyphLayout.shape(
            sections = listOf(TextSection(text)),
            glyphs = { _, _ -> glyph },
            defaultFontStack = listOf("Test"),
            fontSize = FONT_SIZE,
            letterSpacing = 0f,
            lineHeight = 1.2f,
            maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER,
            writingMode = null,
            transform = TEXT_TRANSFORM_NONE,
        )
        val rendered = assertNotNull(
            GlyphRasterizer.render(shaped, Color.Red, Color.Transparent, 0f, 0f)
        )
        val quads = assertNotNull(
            GlyphRasterizer.renderGlyphs(shaped, Color.Red, Color.Transparent, 0f, 0f)
        )
        return LabelArt.Glyphs(rendered, text, quads)
    }

    private fun inkIn(bitmap: androidx.compose.ui.graphics.ImageBitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) if (pixels[x, y].alpha > 0.5f) count++
        }
        return count
    }

    @Test
    fun `a label drawn around a corner puts ink in both arms`() {
        val art = label("aaaaaa")
        val path = LabelPath(
            points = listOf(Offset(20f, 80f), Offset(80f, 80f), Offset(80f, 140f)),
            anchorIndex = 1,
        )
        val quads = assertNotNull(art.quads)
        val placements = assertNotNull(
            SymbolProjection.placeGlyphsAlongPath(
                path = path.points,
                anchorDistance = 60f,
                offsets = FloatArray(quads.size) { quads[it].alongOffset },
            )
        )

        val bitmap = renderToBitmap(size = SIZE) {
            drawTextAlongPath(art, placements, alpha = 1f, scale = 1f)
        }

        assertTrue(inkIn(bitmap, 20, 60, 78, 100) > 0, "ink along the horizontal arm")
        assertTrue(inkIn(bitmap, 60, 100, 100, 140) > 0, "ink along the vertical arm")
        assertEquals(0, inkIn(bitmap, 0, 0, 20, 40), "nothing where the label does not run")
    }

    @Test
    fun `a label drawn along a straight path stays on the line`() {
        val art = label("aaaa")
        val quads = assertNotNull(art.quads)
        val placements = assertNotNull(
            SymbolProjection.placeGlyphsAlongPath(
                path = listOf(Offset(10f, 80f), Offset(150f, 80f)),
                anchorDistance = 70f,
                offsets = FloatArray(quads.size) { quads[it].alongOffset },
            )
        )

        val bitmap = renderToBitmap(size = SIZE) {
            drawTextAlongPath(art, placements, alpha = 1f, scale = 1f)
        }

        assertTrue(inkIn(bitmap, 0, 60, SIZE, 100) > 0)
        assertEquals(0, inkIn(bitmap, 0, 0, SIZE, 55), "the label does not wander off its line")
        assertEquals(0, inkIn(bitmap, 0, 110, SIZE, SIZE), "nor below it")
    }
}
