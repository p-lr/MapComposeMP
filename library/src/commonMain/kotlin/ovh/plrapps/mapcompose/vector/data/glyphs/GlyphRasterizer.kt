package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
 * Draws a [ShapedLabel]'s SDF glyphs, and any inline image among them, into a bitmap.
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
     * The widest or tallest a composited label may be, in pixels.
     *
     * Upstream needs no such bound: it rasterizes every glyph once into a shared atlas and draws a
     * label as quads sampling it, so its ceiling is the atlas texture and a long label costs quads.
     * [render] instead allocates one bitmap covering the whole label, which is what has to be
     * bounded -- and bounded here, on the allocation, rather than on the label's character count:
     * a count says nothing about a short label at a huge `text-size`, and refuses a long one that
     * would rasterize to very little.
     *
     * Both constants are far above any label a style means to draw. At density 3 and `text-size`
     * 16 dp the spec's default `text-max-width` of 10 ems wraps at roughly 480 px, so
     * [MAX_LABEL_BITMAP_PIXELS] is first reached somewhere past a thousand characters; the
     * dimension is only reached by an unwrapped line label far longer than the line it would have
     * to fit on.
     */
    const val MAX_LABEL_BITMAP_DIMENSION = 8192

    /** The most pixels a composited label may cover -- 4 M, i.e. 16 MB as an `IntArray`. */
    const val MAX_LABEL_BITMAP_PIXELS = 4_194_304

    /**
     * Whether a label of this size is worth allocating.
     *
     * A label past either bound is not dropped: [render] returns null, `TextLabelBuilder` falls
     * through to its Compose measure, which caps itself at a handful of lines.
     */
    private fun isRasterizable(width: Int, height: Int): Boolean =
        width <= MAX_LABEL_BITMAP_DIMENSION &&
            height <= MAX_LABEL_BITMAP_DIMENSION &&
            width.toLong() * height <= MAX_LABEL_BITMAP_PIXELS

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
        val items = label.items.filter { it !is ShapedGlyph || it.glyph.hasBitmap }
        if (items.isEmpty()) return null

        // The bitmap covers every glyph's distance field, which reaches GLYPH_BORDER samples
        // outside the ink -- that margin is exactly the room the halo has to grow into. An inline
        // image is a plain picture, so it gets no such margin.
        var minX = 0f
        var minY = 0f
        var maxX = label.width
        var maxY = label.height
        for (item in items) {
            when (item) {
                is ShapedGlyph -> {
                    val border = GLYPH_BORDER * item.scale
                    minX = min(minX, item.inkLeft - border)
                    minY = min(minY, item.inkTop - border)
                    maxX = max(maxX, item.inkLeft + item.inkWidth + border)
                    maxY = max(maxY, item.inkTop + item.inkHeight + border)
                }

                is ShapedImage -> {
                    minX = min(minX, item.x)
                    minY = min(minY, item.y)
                    maxX = max(maxX, item.x + item.width)
                    maxY = max(maxY, item.y + item.height)
                }
            }
        }

        val width = ceil(maxX - minX).toInt()
        val height = ceil(maxY - minY).toInt()
        if (width <= 0 || height <= 0) return null
        if (!isRasterizable(width, height)) return null

        val pixels = IntArray(width * height)

        /* In shaping order, so an inline image and the halo of the glyph beside it composite the
         * way they were laid out. */
        for (item in items) {
            if (item is ShapedImage) {
                val image = item.payload as? ImageBitmap
                if (image != null) {
                    compositeImage(
                        destination = pixels,
                        destinationWidth = width,
                        destinationHeight = height,
                        image = image,
                        originX = item.x - minX,
                        originY = item.y - minY,
                        drawWidth = item.width,
                        drawHeight = item.height,
                        opacity = opacity,
                    )
                }
                continue
            }
            val shaped = item as ShapedGlyph
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
        val items = label.items.filter { it !is ShapedGlyph || it.glyph.hasBitmap }
        if (items.isEmpty()) return null

        val centreX = label.width / 2f
        val centreY = label.height / 2f
        val out = ArrayList<RenderedGlyph>(items.size)

        for (item in items) {
            if (item is ShapedImage) {
                val image = item.payload as? ImageBitmap ?: continue
                val imageWidth = ceil(item.width).toInt()
                val imageHeight = ceil(item.height).toInt()
                if (imageWidth <= 0 || imageHeight <= 0) continue
                val imagePixels = IntArray(imageWidth * imageHeight)
                val inked = compositeImage(
                    destination = imagePixels,
                    destinationWidth = imageWidth,
                    destinationHeight = imageHeight,
                    image = image,
                    originX = 0f,
                    originY = 0f,
                    drawWidth = item.width,
                    drawHeight = item.height,
                    opacity = opacity,
                )
                if (!inked) continue
                val imageCentreX = item.x + item.advance / 2f
                out += RenderedGlyph(
                    bitmap = imageBitmapFromArgb(imagePixels, imageWidth, imageHeight),
                    alongOffset = imageCentreX - centreX,
                    left = item.x - imageCentreX,
                    top = item.y - centreY,
                    width = imageWidth.toFloat(),
                    height = imageHeight.toFloat(),
                )
                continue
            }
            val shaped = item as ShapedGlyph
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
     * Blits an inline image into a label's pixels.
     *
     * Upstream's `symbol_text_and_icon.fragment.glsl` short-circuits an image quad --
     * `if (v_is_sdf == ICON) { fragColor = texture(u_texture_icon, tex_icon) * total_opacity; }` --
     * so a plain image keeps its own colours and only `text-opacity` scales it. An SDF entry is
     * recoloured by the *text* paint before it gets here, which is that shader's other branch, so
     * either way this stays a resample.
     *
     * @return whether anything was drawn.
     */
    private fun compositeImage(
        destination: IntArray,
        destinationWidth: Int,
        destinationHeight: Int,
        image: ImageBitmap,
        originX: Float,
        originY: Float,
        drawWidth: Float,
        drawHeight: Float,
        opacity: Float,
    ): Boolean {
        if (drawWidth <= 0f || drawHeight <= 0f) return false
        if (image.width <= 0 || image.height <= 0) return false

        val source = image.toPixelMap()
        val fromX = max(0, floor(originX).toInt())
        val fromY = max(0, floor(originY).toInt())
        val toX = min(destinationWidth, ceil(originX + drawWidth).toInt())
        val toY = min(destinationHeight, ceil(originY + drawHeight).toInt())
        val scaleX = image.width / drawWidth
        val scaleY = image.height / drawHeight
        var hasInk = false

        for (y in fromY until toY) {
            val v = (y + 0.5f - originY) * scaleY - 0.5f
            for (x in fromX until toX) {
                val u = (x + 0.5f - originX) * scaleX - 0.5f
                val color = sampleBilinear(source, image.width, image.height, u, v)
                if (color.alpha <= 0f) continue
                val index = y * destinationWidth + x
                destination[index] = over(color.withAlphaScaled(opacity), destination[index])
                hasInk = true
            }
        }
        return hasInk
    }

    /**
     * Bilinear read of a sprite, edge-clamped as a `CLAMP_TO_EDGE` texture is.
     *
     * The samples are mixed **premultiplied** and unpremultiplied back. A [PixelMap] is straight
     * alpha, and interpolating that drags a fully transparent pixel's colour -- usually black --
     * into the edge of everything beside it, which is the same trap `circleGradientStops` documents.
     */
    private fun sampleBilinear(source: PixelMap, width: Int, height: Int, x: Float, y: Float): Color {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        var red = 0f
        var green = 0f
        var blue = 0f
        var alpha = 0f
        for (dy in 0..1) {
            for (dx in 0..1) {
                val weight = (if (dx == 0) 1f - fx else fx) * (if (dy == 0) 1f - fy else fy)
                if (weight <= 0f) continue
                val sample = source[
                    (x0 + dx).coerceIn(0, width - 1),
                    (y0 + dy).coerceIn(0, height - 1),
                ]
                val premultiplied = sample.alpha * weight
                red += sample.red * premultiplied
                green += sample.green * premultiplied
                blue += sample.blue * premultiplied
                alpha += premultiplied
            }
        }
        if (alpha <= 0f) return Color.Transparent
        return Color(
            red = (red / alpha).coerceIn(0f, 1f),
            green = (green / alpha).coerceIn(0f, 1f),
            blue = (blue / alpha).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f),
        )
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
