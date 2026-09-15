package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_NONE
import kotlin.math.abs
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
        /**
         * A font's ascent in glyph units: how far a glyph's pen sits above the baseline.
         *
         * `Glyph.top` is negative-upward from the pen, so a real font stack has
         * `top = height - ascent` -- the value decoded from any published range. A fixture with
         * `top = height` has the opposite sign and hides a whole line of vertical error.
         */
        const val ASCENT = 22
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
            left = 0, top = INK - ASCENT, advance = ADVANCE,
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
    fun `a glyph rasterized on its own carries the same ink as it does in a label`() {
        // The invariant that keeps a line label looking the same whichever path draws it: the two
        // rasterizers run the same shading, so only the sampling grid's sub-pixel phase differs.
        val label = shape("a")
        val whole = assertNotNull(GlyphRasterizer.render(label, Color.Black, Color.Transparent, 0f, 0f))
        val quads = assertNotNull(
            GlyphRasterizer.renderGlyphs(label, Color.Black, Color.Transparent, 0f, 0f)
        )

        assertEquals(1, quads.size)
        val quad = quads.single()
        val quadInk = quad.bitmap.toPixelMap().let { pixels ->
            var count = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) if (pixels[x, y].alpha > 0.5f) count++
            }
            count
        }
        assertTrue(
            abs(quadInk - whole.inkCount()) <= whole.inkCount() / 10,
            "one glyph alone should ink the same area as it does in the label",
        )
    }

    @Test
    fun `every glyph quad is placed by its own centre`() {
        val label = shape("aaa")
        val quads = assertNotNull(
            GlyphRasterizer.renderGlyphs(label, Color.Black, Color.Transparent, 0f, 0f)
        )

        assertEquals(3, quads.size)
        val advance = ADVANCE * FONT_SIZE / ONE_EM
        // Symmetric about the label's centre, one advance apart.
        assertEquals(-advance, quads[0].alongOffset, 1e-2f)
        assertEquals(0f, quads[1].alongOffset, 1e-2f)
        assertEquals(advance, quads[2].alongOffset, 1e-2f)
        // The quad's own box is centred on that point, border included.
        val border = GLYPH_BORDER * FONT_SIZE / ONE_EM
        assertEquals(-(advance / 2f + border), quads[1].left, 1e-2f)
    }

    @Test
    fun `a label with no ink has no quads`() {
        assertNull(GlyphRasterizer.renderGlyphs(shape("  "), Color.Black, Color.Transparent, 0f, 0f))
    }

    /**
     * A glyph whose distance field is encoded the way a real range is: [SDF_FILL_BUFFER] at the ink
     * edge, moving by `1 / SDF_PX` per glyph unit. [blockGlyph]'s ramp is steeper than that, which
     * makes it fine for "is there a halo at all" but useless for measuring how wide one is.
     */
    private fun sdfGlyph(id: Int = 'a'.code): Glyph {
        val size = INK + 2 * GLYPH_BORDER
        val bitmap = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inset = minOf(x, y, size - 1 - x, size - 1 - y)
                val distance = (0.75f + (inset - GLYPH_BORDER) / 8f).coerceIn(0f, 1f)
                bitmap[y * size + x] = (distance * 255f).toInt().toByte()
            }
        }
        return Glyph(
            id = id, bitmap = bitmap, width = INK, height = INK,
            left = 0, top = INK - ASCENT, advance = ADVANCE,
        )
    }

    @Test
    fun `the halo is as wide as text-halo-width asks for`() {
        // Pins the halo in pixels rather than by inequality: upstream's `buff` moves the threshold
        // by one distance step per glyph unit of `halo_width / fontScale`, so a 3 px halo on a 48 px
        // label -- fontScale 2 -- reaches 1.5 glyph units, which is 3 device pixels of ink.
        val fontSize = 48f
        val haloWidth = 3f
        val label = GlyphLayout.shape(
            sections = listOf(TextSection("a")),
            glyphs = { _, _ -> sdfGlyph() },
            defaultFontStack = listOf("Test"),
            fontSize = fontSize,
            letterSpacing = 0f, lineHeight = 1.2f, maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER, writingMode = null, transform = TEXT_TRANSFORM_NONE,
        )
        val rendered = assertNotNull(
            GlyphRasterizer.render(label, Color.Red, Color.Blue, haloWidth, 0f)
        )

        val pixels = rendered.bitmap.toPixelMap()
        val glyph = label.glyphs.single()
        val row = (rendered.boxTop + glyph.inkTop + glyph.inkHeight / 2f).toInt()
            .coerceIn(0, pixels.height - 1)

        var halo = 0
        for (x in 0 until pixels.width) {
            val pixel = pixels[x, row]
            if (pixel.alpha > 0.5f && pixel.blue > pixel.red) halo++
        }

        // Both sides of the glyph, so twice the width; the field carries GLYPH_BORDER units, which
        // at this font scale is 6 device pixels -- comfortably more than the 3 asked for.
        val perSide = halo / 2f
        assertTrue(
            abs(perSide - haloWidth) <= 1f,
            "expected about $haloWidth device pixels of halo per side, got $perSide"
        )
    }

    @Test
    fun `a halo wider than the distance field is clipped rather than saturated`() {
        // The field only carries GLYPH_BORDER units of outside distance. Upstream lets the halo
        // threshold run past the end of it and taper; clamping it at zero, as this used to, painted
        // the whole border ring at full strength with a hard edge instead.
        val fontSize = 24f
        val label = GlyphLayout.shape(
            sections = listOf(TextSection("a")),
            glyphs = { _, _ -> sdfGlyph() },
            defaultFontStack = listOf("Test"),
            fontSize = fontSize,
            letterSpacing = 0f, lineHeight = 1.2f, maxWidth = 0f,
            justify = TEXT_JUSTIFY_CENTER, writingMode = null, transform = TEXT_TRANSFORM_NONE,
        )
        val wide = assertNotNull(GlyphRasterizer.render(label, Color.Red, Color.Blue, 12f, 0f))
        val wider = assertNotNull(GlyphRasterizer.render(label, Color.Red, Color.Blue, 24f, 0f))

        fun haloPixels(rendered: RenderedLabel): Int {
            val pixels = rendered.bitmap.toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val pixel = pixels[x, y]
                    if (pixel.alpha > 0.5f && pixel.blue > pixel.red) count++
                }
            }
            return count
        }

        assertTrue(
            haloPixels(wider) <= haloPixels(wide),
            "past the field's reach a wider halo cannot cover more ground"
        )
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

    // region inline images -- upstream's `symbol_text_and_icon` icon branch

    /** A solid square, standing in for a sprite the sheet resolved. */
    private fun solidImage(color: Color, size: Int = 8): ImageBitmap =
        imageBitmapFromArgb(IntArray(size * size) { color.toArgb() }, size, size)

    private fun shapeWithImage(
        text: String,
        image: ImageBitmap,
        width: Float,
        height: Float,
    ): ShapedLabel = GlyphLayout.shape(
        sections = buildList {
            if (text.isNotEmpty()) add(TextSection(text))
            add(TextSection(text = "", image = SectionImage(width, height, image)))
        },
        glyphs = { _, _ -> blockGlyph() },
        defaultFontStack = listOf("Test"),
        fontSize = FONT_SIZE, letterSpacing = 0f, lineHeight = 1.2f, maxWidth = 0f,
        justify = TEXT_JUSTIFY_CENTER, writingMode = null, transform = TEXT_TRANSFORM_NONE,
    )

    @Test
    fun `an inline image is drawn into the label bitmap`() {
        val label = shapeWithImage("", solidImage(Color.Blue), width = 20f, height = 20f)
        val rendered = assertNotNull(
            GlyphRasterizer.render(label, Color.Black, Color.Transparent, 0f, 0f)
        )
        assertEquals(20f, rendered.boxWidth)

        val placed = label.items.filterIsInstance<ShapedImage>().single()
        val pixels = rendered.bitmap.toPixelMap()
        val x = (rendered.boxLeft + placed.x + placed.width / 2f).toInt()
        val y = (rendered.boxTop + placed.y + placed.height / 2f).toInt()
        assertColorEquals(Color.Blue, pixels[x, y], tolerance = 0.02f)
    }

    @Test
    fun `text-opacity scales an inline image`() {
        // Upstream's `fragColor = texture(u_texture_icon, tex_icon) * total_opacity`.
        val label = shapeWithImage("", solidImage(Color.Blue), width = 20f, height = 20f)
        val rendered = assertNotNull(
            GlyphRasterizer.render(label, Color.Black, Color.Transparent, 0f, 0f, opacity = 0.5f)
        )
        val placed = label.items.filterIsInstance<ShapedImage>().single()
        val pixels = rendered.bitmap.toPixelMap()
        val x = (rendered.boxLeft + placed.x + placed.width / 2f).toInt()
        val y = (rendered.boxTop + placed.y + placed.height / 2f).toInt()
        assertTrue(
            abs(pixels[x, y].alpha - 0.5f) < 0.02f,
            "an inline image should carry text-opacity, got ${pixels[x, y].alpha}",
        )
    }

    @Test
    fun `a line label carries a quad for its inline image`() {
        val label = shapeWithImage("a", solidImage(Color.Blue), width = 20f, height = 20f)
        val quads = assertNotNull(
            GlyphRasterizer.renderGlyphs(label, Color.Black, Color.Transparent, 0f, 0f)
        )
        assertEquals(2, quads.size)

        val imageQuad = quads.last()
        val placed = label.items.filterIsInstance<ShapedImage>().single()
        assertEquals(placed.x + placed.advance / 2f - label.width / 2f, imageQuad.alongOffset, 0.001f)
        val pixels = imageQuad.bitmap.toPixelMap()
        assertColorEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2], tolerance = 0.02f)
    }

    // endregion

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
