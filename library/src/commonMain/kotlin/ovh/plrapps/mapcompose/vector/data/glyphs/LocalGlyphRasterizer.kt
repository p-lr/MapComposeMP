package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws one grapheme with a font file the style declared, and hands back a [Glyph] the rest of the
 * pipeline cannot tell from one a glyph server served.
 *
 * Taken as an interface for the reason upstream takes `CreateRasterizer`: the production
 * implementation needs a real text stack and a real bitmap, and a test wants neither.
 */
interface LocalGlyphSource {
    /**
     * @param family the font file to draw with, as its own [FontFamily] -- see [fontFamilyFromBytes]
     * @param grapheme one grapheme cluster, drawn whole so a letter keeps its marks
     */
    suspend fun rasterize(family: FontFamily, grapheme: String): Glyph?
}

/**
 * [LocalGlyphSource] over Compose's text stack, which is this port's stand-in for upstream's canvas.
 *
 * A port of `GlyphManager._drawGlyph` and `_createTinySDF` (`src/render/glyph_manager.ts`): the
 * grapheme is drawn at `24 * textureScale` pixels, the coverage is turned into a distance field by
 * [TinySdf], and the metrics are divided back down by that same scale with upstream's two
 * calibration constants applied.
 *
 * **The field is halved, where upstream keeps it.** Upstream hands the atlas a double-resolution
 * bitmap with single-resolution metrics and flags it `isDoubleResolution`; nothing here carries such
 * a flag -- [Glyph] promises `bitmapWidth == width + 2 * GLYPH_BORDER`, and every reader from
 * `GlyphRasterizer` to the `text-halo-width` shading assumes it -- so the 2x field is averaged back
 * down to the glyph units the pbf path works in. A byte means the same distance either way: the
 * field is normalized by `radius`, and the radius is scaled with the rasterization.
 *
 * Nothing is cached here. [GlyphManager] is what remembers a drawn grapheme, keyed by the file it
 * was drawn with, the same way it remembers a downloaded range.
 */
internal class ComposeGlyphRasterizer(
    private val textMeasurerState: StateFlow<TextMeasurer?>,
) : LocalGlyphSource {

    override suspend fun rasterize(family: FontFamily, grapheme: String): Glyph? {
        if (grapheme.isEmpty()) return null
        val measurer = textMeasurerState.filterNotNull().first()
        val id = grapheme.codePointAtCompat(0)

        val style = TextStyle(color = Color.White, fontFamily = family, fontSize = FONT_SIZE_PX.sp)
        val layout = runCatching { measurer.layout(grapheme, style) }.getOrNull() ?: return null
        val advance = runCatching { measurer.advanceOf(grapheme, style) }.getOrNull() ?: return null
        val bitmapWidth = ceil(advance).toInt() + 2 * MARGIN
        val bitmapHeight = layout.size.height + 2 * MARGIN
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

        val target = ImageBitmap(bitmapWidth, bitmapHeight)
        CanvasDrawScope().draw(
            density = DENSITY,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(target),
            size = Size(bitmapWidth.toFloat(), bitmapHeight.toFloat()),
        ) {
            drawText(textLayoutResult = layout, topLeft = Offset(MARGIN.toFloat(), MARGIN.toFloat()))
        }

        val coverage = Coverage.of(target)
        val originX = MARGIN + layout.getLineLeft(0)
        val baselineY = MARGIN + layout.firstBaseline

        /* A grapheme with no ink -- a space, a control character -- still carries its advance, which
         * is what keeps the pen moving. `Glyph.hasBitmap` is false for it, as it is for a space
         * served by a glyph server. */
        val ink = coverage.inkBounds() ?: return Glyph(
            id = id,
            bitmap = null,
            width = 0,
            height = 0,
            left = 0,
            top = 0,
            advance = (advance / TEXTURE_SCALE).roundToInt(),
        )

        /* An odd ink box cannot be halved into the glyph-unit field, so it is grown by a column or a
         * row of empty coverage -- the same thing a wider ink box would have contributed. */
        val glyphWidth = ink.width + (ink.width % 2)
        val glyphHeight = ink.height + (ink.height % 2)
        val alpha = coverage.alphaBox(ink.left, ink.top, glyphWidth, glyphHeight)

        val field = TinySdf.render(
            alpha = alpha,
            glyphWidth = glyphWidth,
            glyphHeight = glyphHeight,
            buffer = PADDING,
            radius = RADIUS,
            cutoff = CUTOFF,
        )

        return Glyph(
            id = id,
            bitmap = halve(field, glyphWidth + 2 * PADDING, glyphHeight + 2 * PADDING),
            width = glyphWidth / TEXTURE_SCALE,
            height = glyphHeight / TEXTURE_SCALE,
            /* Upstream's `leftAdjustment` and `topAdjustment`, which line a locally drawn glyph up
             * with the server's: its `top` is measured from an origin above the em box, 27.5 glyph
             * units above the baseline. */
            left = ((ink.left - originX) / TEXTURE_SCALE + LEFT_ADJUSTMENT).roundToInt(),
            top = ((baselineY - ink.top) / TEXTURE_SCALE - TOP_ADJUSTMENT).roundToInt(),
            advance = (advance / TEXTURE_SCALE).roundToInt(),
        )
    }

    /** The alpha channel of a drawn grapheme, as plain samples. */
    private class Coverage(val alpha: IntArray, val width: Int, val height: Int) {

        fun inkBounds(): InkBounds? {
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (alpha[row + x] == 0) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < minX || maxY < minY) return null
            return InkBounds(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }

        /** The coverage of a box, zero outside the bitmap, so a box may be grown past the ink. */
        fun alphaBox(left: Int, top: Int, boxWidth: Int, boxHeight: Int): IntArray {
            val out = IntArray(boxWidth * boxHeight)
            for (y in 0 until boxHeight) {
                val sourceY = top + y
                if (sourceY !in 0 until height) continue
                for (x in 0 until boxWidth) {
                    val sourceX = left + x
                    if (sourceX !in 0 until width) continue
                    out[y * boxWidth + x] = alpha[sourceY * width + sourceX]
                }
            }
            return out
        }

        companion object {
            fun of(bitmap: ImageBitmap): Coverage {
                val pixels = bitmap.toPixelMap()
                val alpha = IntArray(pixels.width * pixels.height)
                for (y in 0 until pixels.height) {
                    val source = pixels.bufferOffset + y * pixels.stride
                    val target = y * pixels.width
                    for (x in 0 until pixels.width) {
                        /* The buffer's own ARGB rather than `PixelMap.get`, which builds a `Color`
                         * per pixel -- and this reads every pixel of every grapheme drawn. */
                        alpha[target + x] = (pixels.buffer[source + x] ushr 24) and 0xFF
                    }
                }
                return Coverage(alpha, pixels.width, pixels.height)
            }
        }
    }

    private class InkBounds(val left: Int, val top: Int, val width: Int, val height: Int)

    /** One run, laid out in its own space: one line, no wrapping, one pixel per pixel. */
    private fun TextMeasurer.layout(text: String, style: TextStyle) = measure(
        text = AnnotatedString(text),
        style = style,
        softWrap = false,
        maxLines = 1,
        layoutDirection = LayoutDirection.Ltr,
        /* The measure's own density, not the map's: it is what makes `FONT_SIZE_PX.sp` exactly that
         * many pixels, whatever the screen and the user's font scale. */
        density = DENSITY,
    )

    /**
     * How far the pen moves past [text] -- upstream's `measureText(char).width`.
     *
     * Measured between two sentinels rather than on its own, because a line's width has its
     * **trailing whitespace trimmed**: a space measures zero that way, and a label's spaces would
     * then close up into one word. Neither sentinel is trailing, so nothing is trimmed, and
     * subtracting the pair's own width leaves the run's advance.
     */
    private fun TextMeasurer.advanceOf(text: String, style: TextStyle): Float {
        val sentinels = layout(SENTINEL + SENTINEL, style).lineWidth()
        val sandwiched = layout(SENTINEL + text + SENTINEL, style).lineWidth()
        return max(0f, sandwiched - sentinels)
    }

    private fun TextLayoutResult.lineWidth(): Float = max(0f, getLineRight(0) - getLineLeft(0))

    private companion object {
        /**
         * Upstream's `textureScale`: a glyph is drawn at twice its size, "because CJK glyphs are
         * more detailed than others", and everything measured off it is divided back down.
         */
        const val TEXTURE_SCALE = 2

        const val FONT_SIZE_PX = ONE_EM * TEXTURE_SCALE

        /** Upstream's `padding`, which is [GLYPH_BORDER] at the rasterization's own scale. */
        const val PADDING = GLYPH_BORDER * TEXTURE_SCALE

        const val RADIUS = 8.0 * TEXTURE_SCALE
        const val CUTOFF = 0.25

        /** Upstream's calibration between a locally drawn glyph and a server-generated one. */
        const val TOP_ADJUSTMENT = 27.5f
        const val LEFT_ADJUSTMENT = 0.5f

        /**
         * Room left around the text's own box, so ink reaching past it -- a mark above the ascent,
         * an italic overhang -- is still drawn. Upstream sizes its canvas the same way, at
         * `fontSize + buffer * 4`.
         */
        const val MARGIN = FONT_SIZE_PX

        /** One pixel per pixel: a glyph is rasterized in its own space, never the screen's. */
        val DENSITY = Density(1f)

        /**
         * What an advance is measured between.
         *
         * A letter rather than a mark or a space, so that it is never trimmed and never joins what
         * it sits beside: a mark would attach itself to the run and change the very thing being
         * measured.
         */
        const val SENTINEL = "X"

        /** Averages a 2x2 block of the distance field into one sample. */
        fun halve(field: ByteArray, width: Int, height: Int): ByteArray {
            val outWidth = width / 2
            val outHeight = height / 2
            val out = ByteArray(outWidth * outHeight)
            for (y in 0 until outHeight) {
                for (x in 0 until outWidth) {
                    val topLeft = (2 * y) * width + 2 * x
                    val sum = (field[topLeft].toInt() and 0xFF) +
                        (field[topLeft + 1].toInt() and 0xFF) +
                        (field[topLeft + width].toInt() and 0xFF) +
                        (field[topLeft + width + 1].toInt() and 0xFF)
                    out[y * outWidth + x] = ((sum + 2) / 4).coerceIn(0, 255).toByte()
                }
            }
            return out
        }
    }
}
