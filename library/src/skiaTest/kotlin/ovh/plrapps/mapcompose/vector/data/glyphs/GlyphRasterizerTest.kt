package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drawing a shaped label's SDF glyphs.
 *
 * In `skiaTest` because it allocates an `ImageBitmap`; the shading maths it depends on is covered
 * without a graphics backend by `SdfShadingTest`, and the shaping by `GlyphLayoutTest`.
 *
 * The fake glyph is a distance field that is solid in the middle and ramps to nothing at its border,
 * so "inside the glyph" and "just outside it" are both addressable by pixel.
 */
class GlyphRasterizerTest {

    private companion object {
        const val INK = 12
        const val ADVANCE = 24
        const val FONT_SIZE = 24f
    }

    /**
     * A square glyph whose distance field is 1 across the ink and falls linearly to 0 across the
     * [GLYPH_BORDER] ring, which is the shape a real SDF range has at a straight edge.
     */
    private fun blockGlyph(id: Int = 'a'.code): Glyph {
        val size = INK + 2 * GLYPH_BORDER
        val bitmap = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inset = minOf(x, y, size - 1 - x, size - 1 - y)
                val distance = (inset.toFloat() / GLYPH_BORDER).coerceIn(0f, 1f)
                bitmap[y * size + x] = (distance * 255f).toInt().toByte()
            }
        }
        return Glyph(
            id = id, bitmap = bitmap, width = INK, height = INK,
            left = 0, top = INK, advance = ADVANCE,
        )
    }

    private fun shape(text: String, glyph: Glyph = blockGlyph()): ShapedLabel =
        GlyphLayout.shape(
            sections = listOf(TextSection(text)),
            glyphs = { _, code -> if (code == 0x20) Glyph(code, null, 0, 0, 0, 0, ADVANCE) else glyph },
            defaultFontStack = listOf("Test"),
            fontSize = FONT_SIZE,
            letterSpacing = 0f,
            lineHeight = 1.2f,
            maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER,
            writingMode = null,
            transform = TEXT_TRANSFORM_NONE,
        )

    private fun render(
        text: String = "a",
        fill: Color = Color.Black,
        halo: Color = Color.Transparent,
        haloWidth: Float = 0f,
        haloBlur: Float = 0f,
        opacity: Float = 1f,
    ) = GlyphRasterizer.render(
        label = shape(text),
        fillColor = fill,
        haloColor = halo,
        haloWidth = haloWidth,
        haloBlur = haloBlur,
        opacity = opacity,
    )

    /** Pixels that are more than half opaque. */
    private fun RenderedLabel.inkCount(): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0.5f) count++
            }
        }
        return count
    }

    @Test
    fun `a label with no ink renders nothing`() {
        val label = GlyphLayout.shape(
            sections = listOf(TextSection("   ")),
            glyphs = { _, code -> Glyph(code, null, 0, 0, 0, 0, ADVANCE) },
            defaultFontStack = listOf("Test"),
            fontSize = FONT_SIZE, letterSpacing = 0f, lineHeight = 1.2f, maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER, writingMode = null, transform = TEXT_TRANSFORM_NONE,
        )
        assertNull(
            GlyphRasterizer.render(label, Color.Black, Color.Transparent, 0f, 0f)
        )
    }

    @Test
    fun `a glyph is drawn in the fill colour`() {
        val rendered = assertNotNull(render(fill = Color.Red))
        val pixels = rendered.bitmap.toPixelMap()
        // The centre of the first glyph's ink.
        val x = (rendered.boxLeft + INK * FONT_SIZE / ONE_EM / 2f).toInt()
        val y = (rendered.boxTop + rendered.boxHeight * 0.4f).toInt()
        assertColorEquals(Color.Red, pixels[x, y], tolerance = 0.05f)
    }

    @Test
    fun `the bitmap reaches outside the label box`() {
        // Ascenders, descenders and the distance field's border ring all sit outside the box, which
        // is why the box's own corner has to be reported rather than assumed to be the origin.
        val rendered = assertNotNull(render())
        assertTrue(rendered.boxLeft > 0f, "expected a left margin, got ${rendered.boxLeft}")
        assertTrue(rendered.bitmap.width >= rendered.boxWidth.toInt())
    }

    @Test
    fun `a halo covers more ground than the fill alone`() {
        val plain = assertNotNull(render(fill = Color.Red)).inkCount()
        val haloed = assertNotNull(
            render(fill = Color.Red, halo = Color.Blue, haloWidth = 1.5f)
        ).inkCount()
        assertTrue(haloed > plain, "a halo must widen the glyph, got $haloed vs $plain")
    }

    @Test
    fun `a wider halo covers more than a narrow one`() {
        val narrow = assertNotNull(render(fill = Color.Red, halo = Color.Blue, haloWidth = 0.5f)).inkCount()
        val wide = assertNotNull(render(fill = Color.Red, halo = Color.Blue, haloWidth = 2f)).inkCount()
        assertTrue(wide > narrow, "got $wide vs $narrow")
    }

    @Test
    fun `the halo is drawn behind the fill`() {
        // Summing the two passes, as the old icon path did, would tint the glyph's interior.
        val rendered = assertNotNull(
            render(fill = Color.Red, halo = Color.Blue, haloWidth = 2f)
        )
        val pixels = rendered.bitmap.toPixelMap()
        val x = (rendered.boxLeft + INK * FONT_SIZE / ONE_EM / 2f).toInt()
        val y = (rendered.boxTop + rendered.boxHeight * 0.4f).toInt()
        assertColorEquals(Color.Red, pixels[x, y], tolerance = 0.05f)
    }

    @Test
    fun `text-opacity scales the drawn alpha`() {
        val opaque = assertNotNull(render(fill = Color.Black))
        val faded = assertNotNull(render(fill = Color.Black, opacity = 0.5f))
        val x = (opaque.boxLeft + INK * FONT_SIZE / ONE_EM / 2f).toInt()
        val y = (opaque.boxTop + opaque.boxHeight * 0.4f).toInt()
        assertEquals(1f, opaque.bitmap.toPixelMap()[x, y].alpha)
        assertColorEquals(
            Color.Black.copy(alpha = 0.5f),
            faded.bitmap.toPixelMap()[x, y],
            tolerance = 0.05f,
        )
    }

    @Test
    fun `a two-glyph label is wider than a one-glyph label`() {
        val one = assertNotNull(render("a"))
        val two = assertNotNull(render("aa"))
        assertEquals(one.boxWidth * 2f, two.boxWidth)
        assertTrue(two.bitmap.width > one.bitmap.width)
    }

    @Test
    fun `a section colour overrides the layer's text-color`() {
        val label = GlyphLayout.shape(
            sections = listOf(TextSection("a", color = Color.Green)),
            glyphs = { _, _ -> blockGlyph() },
            defaultFontStack = listOf("Test"),
            fontSize = FONT_SIZE, letterSpacing = 0f, lineHeight = 1.2f, maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER, writingMode = null, transform = TEXT_TRANSFORM_NONE,
        )
        val rendered = assertNotNull(
            GlyphRasterizer.render(label, Color.Red, Color.Transparent, 0f, 0f)
        )
        val pixels = rendered.bitmap.toPixelMap()
        val x = (rendered.boxLeft + INK * FONT_SIZE / ONE_EM / 2f).toInt()
        val y = (rendered.boxTop + rendered.boxHeight * 0.4f).toInt()
        assertColorEquals(Color.Green, pixels[x, y], tolerance = 0.05f)
    }
}
