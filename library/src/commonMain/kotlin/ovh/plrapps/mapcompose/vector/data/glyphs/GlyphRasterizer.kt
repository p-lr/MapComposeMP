package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import ovh.plrapps.mapcompose.vector.data.imageBitmapFromArgb
import ovh.plrapps.mapcompose.vector.renderer.utils.sdfPixel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A shaped label, drawn.
 *
 * [bitmap] is only as large as the ink needs, which is generally a little larger than the label's
 * own box -- ascenders, descenders and the halo all reach outside it. [boxLeft] and [boxTop] say
 * where the box's top-left corner sits inside the bitmap, so the painter can anchor the *box* and
 * still blit the whole bitmap.
 */
class RenderedLabel(
    val bitmap: ImageBitmap,
    val boxLeft: Float,
    val boxTop: Float,
    val boxWidth: Float,
    val boxHeight: Float,
)

/**
 * One glyph of a label, rasterized on its own -- upstream's glyph quad.
 *
 * A label that follows a line is drawn glyph by glyph, each at its own angle, so the composited
 * [RenderedLabel] cannot be used: it is one flat picture of the label as it would read straight.
 * Blitting sub-rectangles of it does not work either, because a glyph's distance field reaches
 * [GLYPH_BORDER] samples past its ink and so **overlaps** its neighbours' -- a sub-rect would
 * double-composite the halo and drag a slice of the next glyph along at the wrong angle.
 *
 * The geometry is anchored at the glyph's own **centre** (upstream's
 * `glyphOffset = shapedGlyph.x + halfAdvance`), because that is the point the draw pass rotates
 * about: [alongOffset] is the signed arc distance from the label's centre to this glyph's, and
 * [left] / [top] place the bitmap relative to that glyph centre. All in layout pixels.
 */
class RenderedGlyph(
    val bitmap: ImageBitmap,
    val alongOffset: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Draws a [ShapedLabel]'s SDF glyphs into a bitmap.
 *
 * This is the CPU stand-in for maplibre-gl-js's `symbol_sdf` draw pass: every glyph's distance field
 * is sampled and run through [sdfPixel], the same function the SDF *icons* go through, so
 * `text-halo-width`, `text-halo-color` and `text-halo-blur` mean here what they mean upstream -- a
 * dilated outline around the glyph rather than a blur behind it.
 *
 * **Divergences.** Upstream shades in the fragment shader at the final screen resolution; here the
 * label is rasterized once at its layout size and then composited, so a label is resampled if the
 * map scales it -- the same trade every tile painter makes. And a halo can only reach as far as the
 * distance field extends, [GLYPH_BORDER] samples, which is upstream's limit too.
 */
object GlyphRasterizer {

    /**
     * @param opacity `text-opacity`, multiplied into both the fill and the halo as
     * `Color.withOpacity` does elsewhere -- upstream's `u_opacity` applies to the whole symbol.
     * @return `null` when the label has no ink at all, e.g. every codepoint missed the font stack.
     */
    fun render(
        label: ShapedLabel,
        fillColor: Color,
        haloColor: Color,
        haloWidth: Float,
        haloBlur: Float,
        opacity: Float = 1f,
    ): RenderedLabel? {
        val glyphs = label.glyphs.filter { it.glyph.hasBitmap }
        if (glyphs.isEmpty()) return null

        // The bitmap covers every glyph's distance field, which reaches GLYPH_BORDER samples
        // outside the ink -- that margin is exactly the room the halo has to grow into.
        var minX = 0f
        var minY = 0f
        var maxX = label.width
        var maxY = label.height
        for (shaped in glyphs) {
            val border = GLYPH_BORDER * shaped.scale
            minX = min(minX, shaped.inkLeft - border)
            minY = min(minY, shaped.inkTop - border)
            maxX = max(maxX, shaped.inkLeft + shaped.inkWidth + border)
            maxY = max(maxY, shaped.inkTop + shaped.inkHeight + border)
        }

        val width = ceil(maxX - minX).toInt()
        val height = ceil(maxY - minY).toInt()
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)

        for (shaped in glyphs) {
            val border = GLYPH_BORDER * shaped.scale
            val originX = shaped.inkLeft - border - minX
            val originY = shaped.inkTop - border - minY
            val glyph = shaped.glyph

            val fromX = max(0, floor(originX).toInt())
            val fromY = max(0, floor(originY).toInt())
            val toX = min(width, ceil(originX + glyph.bitmapWidth * shaped.scale).toInt())
            val toY = min(height, ceil(originY + glyph.bitmapHeight * shaped.scale).toInt())

            val fill = (shaped.color ?: fillColor).withAlphaScaled(opacity)
            val halo = haloColor.withAlphaScaled(opacity)

            for (y in fromY until toY) {
                for (x in fromX until toX) {
                    // Sample the distance field at the pixel's centre, in bitmap-sample space.
                    val u = (x + 0.5f - originX) / shaped.scale - 0.5f
                    val v = (y + 0.5f - originY) / shaped.scale - 0.5f
                    val distance = sampleBilinear(glyph, u, v)
                    if (distance <= 0f) continue

                    val color = sdfPixel(
                        distance = distance,
                        fillColor = fill,
                        haloColor = halo,
                        haloWidth = haloWidth,
                        haloBlur = haloBlur,
                        fontScale = shaped.scale,
                    )
                    if (color.alpha <= 0f) continue
                    val index = y * width + x
                    pixels[index] = over(color, pixels[index])
                }
            }
        }

        return RenderedLabel(
            bitmap = imageBitmapFromArgb(pixels, width, height),
            boxLeft = -minX,
            boxTop = -minY,
            boxWidth = label.width,
            boxHeight = label.height,
        )
    }

    /**
     * The label's glyphs, each rasterized into its own bitmap.
     *
     * The per-glyph counterpart of [render], for a label that follows a line. Everything about a
     * glyph's shading is identical -- the same [sdfPixel] call with the same arguments -- so a
     * glyph drawn this way carries the same ink as the same glyph inside [render]'s composite, up
     * to the sub-pixel phase of the sampling grid, which is the glyph's own here and the label's
     * there. Compositing *neighbours* is left to the canvas, which is what upstream does too.
     *
     * Returns `null` when the label has no ink, and skips a glyph that has none (a space).
     */
    fun renderGlyphs(
        label: ShapedLabel,
        fillColor: Color,
        haloColor: Color,
        haloWidth: Float,
        haloBlur: Float,
        opacity: Float = 1f,
    ): List<RenderedGlyph>? {
        val glyphs = label.glyphs.filter { it.glyph.hasBitmap }
        if (glyphs.isEmpty()) return null

        val centreX = label.width / 2f
        val centreY = label.height / 2f
        val out = ArrayList<RenderedGlyph>(glyphs.size)

        for (shaped in glyphs) {
            val glyph = shaped.glyph
            val border = GLYPH_BORDER * shaped.scale
            /* Upstream's `quads.ts`: a glyph's quad is positioned by its pen plus half its advance,
             * which is the point the label's arc-length walk steps along. */
            val halfAdvance = glyph.advance * shaped.scale / 2f
            val glyphCentreX = shaped.x + halfAdvance

            val originX = shaped.inkLeft - border
            val originY = shaped.inkTop - border
            val width = ceil(glyph.bitmapWidth * shaped.scale).toInt()
            val height = ceil(glyph.bitmapHeight * shaped.scale).toInt()
            if (width <= 0 || height <= 0) continue

            val pixels = IntArray(width * height)
            val fill = (shaped.color ?: fillColor).withAlphaScaled(opacity)
            val halo = haloColor.withAlphaScaled(opacity)
            var hasInk = false

            for (y in 0 until height) {
                for (x in 0 until width) {
                    /* The sample position matches [render]'s exactly: there the pixel grid is the
                     * label bitmap's and the origin carries a fractional part, here the grid is the
                     * glyph's own, so the origin is at zero. */
                    val u = (x + 0.5f) / shaped.scale - 0.5f
                    val v = (y + 0.5f) / shaped.scale - 0.5f
                    val distance = sampleBilinear(glyph, u, v)
                    if (distance <= 0f) continue

                    val color = sdfPixel(
                        distance = distance,
                        fillColor = fill,
                        haloColor = halo,
                        haloWidth = haloWidth,
                        haloBlur = haloBlur,
                        fontScale = shaped.scale,
                    )
                    if (color.alpha <= 0f) continue
                    pixels[y * width + x] = color.toArgb()
                    hasInk = true
                }
            }
            if (!hasInk) continue

            out += RenderedGlyph(
                bitmap = imageBitmapFromArgb(pixels, width, height),
                alongOffset = glyphCentreX - centreX,
                left = originX - glyphCentreX,
                top = originY - centreY,
                width = width.toFloat(),
                height = height.toFloat(),
            )
        }

        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Bilinear read of a glyph's distance field.
     *
     * Nearest-neighbour would stair-step the edge the field exists to keep smooth, which is why the
     * texture is sampled `LINEAR` upstream.
     */
    private fun sampleBilinear(glyph: Glyph, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val topLeft = glyph.distanceAt(x0, y0)
        val topRight = glyph.distanceAt(x0 + 1, y0)
        val bottomLeft = glyph.distanceAt(x0, y0 + 1)
        val bottomRight = glyph.distanceAt(x0 + 1, y0 + 1)
        val top = topLeft + (topRight - topLeft) * fx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
        return top + (bottom - top) * fy
    }

    /** Source-over of a straight-alpha colour onto a packed ARGB pixel. */
    private fun over(source: Color, destinationArgb: Int): Int {
        if (source.alpha >= 1f) return source.toArgb()
        val destination = Color(destinationArgb)
        val behind = destination.alpha * (1f - source.alpha)
        val alpha = source.alpha + behind
        if (alpha <= 0f) return 0
        return Color(
            red = (source.red * source.alpha + destination.red * behind) / alpha,
            green = (source.green * source.alpha + destination.green * behind) / alpha,
            blue = (source.blue * source.alpha + destination.blue * behind) / alpha,
            alpha = alpha,
        ).toArgb()
    }

    private fun Color.withAlphaScaled(factor: Float): Color =
        if (factor >= 1f) this else copy(alpha = alpha * factor.coerceAtLeast(0f))
}
