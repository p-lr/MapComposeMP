package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_AUTO
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_LEFT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_RIGHT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_LOWERCASE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_UPPERCASE
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.VerticalAlign
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns a label's text into positioned glyphs. A port of maplibre-gl-js `src/symbol/shaping.ts`.
 *
 * Everything happens in *glyph units*, where one em is [ONE_EM], and is scaled to layout pixels once
 * at the end -- which is how upstream keeps `text-letter-spacing`, `text-line-height`,
 * `text-max-width` and `text-offset` in the ems the style spec specifies them in.
 *
 * What is ported: `text-transform`, `text-letter-spacing`, `text-line-height`, `text-max-width` with
 * upstream's balanced line breaking, `text-justify`, the vertical half of `text-writing-mode`, and a
 * `["format", ...]` section's `font-scale`, `text-font`, `text-color`, `vertical-align` and inline
 * `image`.
 *
 * **Divergences.** There is no bidirectional reordering: upstream hands right-to-left text to an
 * optional `rtl-text-plugin` and this has no equivalent, so Arabic and Hebrew shape in logical
 * order. Arabic contextual forms are likewise left to the font. And a codepoint the glyph server has
 * no glyph for is dropped rather than falling back to a locally rendered `TinySDF`.
 */

/**
 * An inline image section -- upstream's `ImageSectionOptions` plus the resolved sprite.
 *
 * [width] and [height] are the size the image is *drawn* at, in the same device pixels the caller's
 * `fontSize` is in; that is upstream's `imagePosition.displaySize` scaled by its
 * `layoutTextSizeFactor`. [payload] is whatever the caller needs to draw it -- an `ImageBitmap` in
 * production -- and is deliberately opaque here, so shaping stays free of any graphics backend and
 * its tests can run on every target.
 */
class SectionImage(val width: Float, val height: Float, val payload: Any)

/** One run of a label that shares a font, size and colour -- a `["format", ...]` section. */
class TextSection(
    val text: String,
    val scale: Float = 1f,
    val fontStack: List<String>? = null,
    val color: Color? = null,
    /** When set, the section *is* this image and [text] is ignored, as `TaggedString` does. */
    val image: SectionImage? = null,
    val verticalAlign: VerticalAlign = VerticalAlign.BOTTOM,
)

/** One positioned thing in a label: a glyph or an inline image. */
sealed class ShapedItem {
    /** The pen position, in layout pixels, relative to the label's top-left. */
    abstract val x: Float
    abstract val y: Float

    /** How far the pen moves past this item, in layout pixels, letter spacing excluded. */
    abstract val advance: Float
}

/** One glyph, placed. [x] and [y] are the pen position on its line's baseline, in layout pixels. */
class ShapedGlyph(
    val glyph: Glyph,
    override val x: Float,
    override val y: Float,
    /** Layout pixels per glyph unit, including the section's own `font-scale`. */
    val scale: Float,
    val color: Color?,
) : ShapedItem() {
    /** The glyph's ink box in layout pixels, relative to the label's top-left. */
    val inkLeft: Float get() = x + glyph.left * scale
    val inkTop: Float get() = y - glyph.top * scale
    val inkWidth: Float get() = glyph.width * scale
    val inkHeight: Float get() = glyph.height * scale
    override val advance: Float get() = glyph.advance * scale
}

/**
 * One inline image, placed.
 *
 * The image hangs *below* the pen: upstream's metrics give an image section `top = -GLYPH_PBF_BORDER`,
 * so its quad starts at the pen and grows downwards, which is also what the bottom-alignment offset
 * assumes.
 */
class ShapedImage(
    val payload: Any,
    override val x: Float,
    override val y: Float,
    val width: Float,
    val height: Float,
) : ShapedItem() {
    override val advance: Float get() = width
}

class ShapedLine(val items: List<ShapedItem>, val width: Float) {
    val glyphs: List<ShapedGlyph> get() = items.filterIsInstance<ShapedGlyph>()
}

/**
 * A shaped label.
 *
 * [width] and [height] are the box the label occupies in layout pixels, with the items positioned
 * relative to its top-left corner. Anchoring that box to a point is the painter's job, not this
 * one's -- see the `text-anchor` handling in `SymbolLayerLayout`.
 */
class ShapedLabel(
    val lines: List<ShapedLine>,
    val width: Float,
    val height: Float,
    val vertical: Boolean,
) {
    val items: List<ShapedItem> get() = lines.flatMap { it.items }
    val glyphs: List<ShapedGlyph> get() = lines.flatMap { it.glyphs }
    val isEmpty: Boolean get() = lines.all { it.items.isEmpty() }
}

/**
 * The first private-use codepoint, upstream's `PUAbegin` (`src/symbol/tagged_string.ts`).
 *
 * An image section contributes exactly one character to the shaped text, and upstream picks it from
 * the private use area so nothing else can collide with it. It matters here for the same reason it
 * does there: line breaking walks codepoints, and an image must be neither breakable nor whitespace.
 */
internal const val PUA_BEGIN = 0xE000

/** Upstream's `PUAend`; past it an image section is dropped. */
internal const val PUA_END = 0xF8FF

object GlyphLayout {

    /**
     * Upstream's `SHAPING_DEFAULT_OFFSET`, `src/symbol/shaping.ts`.
     *
     * A glyph's pen position is **not** its baseline. `Glyph.top` is negative-upward from the pen --
     * upstream's `quads.ts` places a glyph quad at `y1 = (-top - rectBuffer) * scale`, so the ink
     * starts at `pen - top`, which is what [ShapedGlyph.inkTop] computes. In a real font stack every
     * glyph satisfies `height - top = ascent`, so the pen sits on the ascent line and the baseline
     * is the better part of an em below it. Treating the pen as the baseline drops every label by
     * roughly one line of text.
     *
     * Upstream places the first line at this offset from the anchor and lets `align()` add
     * `-verticalAlign * lines * lineHeight + 0.5 * lineHeight`. A shaped label here is a plain box
     * with its top-left at the origin -- anchoring it is the painter's job, one convention for text
     * and icons alike -- and for the centred box that `anchorCenterOffset` produces those two shifts
     * collapse to a per-line pen of `i * lineHeight + lineHeight / 2 + SHAPING_DEFAULT_OFFSET`,
     * independent of the line count.
     *
     * That collapse only holds for upstream's *uniform* branch, `maxLineHeight === lineHeight`. A
     * line grown by an inline image or by a `font-scale` above one takes the other branch, whose
     * `shiftY = -blockHeight * verticalAlign - SHAPING_DEFAULT_OFFSET` leaves the pen of a top-left
     * box at the line's own top -- no half line, no default offset. See [penPositions].
     */
    private const val SHAPING_DEFAULT_OFFSET = -17f

    /** The pen position of the first line within the label's box, in glyph units. */
    private fun firstPenY(lineHeight: Float): Float = 0.5f * lineHeight + SHAPING_DEFAULT_OFFSET

    /**
     * Shapes [sections] into positioned glyphs and images.
     *
     * @param glyphs looks a codepoint up in a font stack. The section's own `text-font` wins over
     * [defaultFontStack], as `["format", ..., {"text-font": [...]}]` is defined to.
     * @param clusters looks a whole *grapheme cluster* up in a font stack, which only a font file
     * the style declared in its root `font-faces` can draw. Non-null only for such a style, and the
     * text is then segmented before it is shaped, so a letter keeps its marks -- upstream's
     * `toGraphemes` (`src/util/graphemes.ts`). Left null, nothing is segmented and every item is one
     * codepoint, which is what every style without the property does.
     * @param fontSize `text-size` in layout pixels.
     * @param letterSpacing `text-letter-spacing` in ems.
     * @param lineHeight `text-line-height` in ems.
     * @param maxWidth `text-max-width` in ems; zero or negative disables wrapping.
     * @param justify `text-justify`; `auto` is resolved by the caller and treated as `center` here.
     * @param vertical set the label vertically, one item per line top to bottom. The caller
     * decides: `text-writing-mode` is a *preference order* resolved at placement time, so a label
     * eligible for it is shaped both ways and the placement pass keeps the one that fits. See
     * [allowsVerticalWritingMode].
     * @param transform `text-transform`.
     */
    fun shape(
        sections: List<TextSection>,
        glyphs: (List<String>, Int) -> Glyph?,
        defaultFontStack: List<String>,
        clusters: ((List<String>, String) -> Glyph?)? = null,
        fontSize: Float,
        letterSpacing: Float,
        lineHeight: Float,
        maxWidth: Float,
        justify: String,
        vertical: Boolean,
        transform: String,
    ): ShapedLabel {
        val scale = fontSize / ONE_EM
        val spacingUnits = letterSpacing * ONE_EM
        val lineHeightUnits = lineHeight * ONE_EM
        val maxWidthUnits = maxWidth * ONE_EM

        /* Upstream's `layoutTextSizeFactor`: an image is sized in layout pixels while everything
         * else is in glyph units, and one glyph unit is `fontSize / ONE_EM` layout pixels. */
        val unitsPerPixel = if (scale > 0f) 1f / scale else 0f

        val items = flatten(sections, transform, glyphs, clusters, defaultFontStack, unitsPerPixel)
        if (items.isEmpty()) return ShapedLabel(emptyList(), 0f, 0f, vertical = false)

        if (vertical) return shapeVertical(items, scale, lineHeightUnits)

        val breaks = determineLineBreaks(items, spacingUnits, maxWidthUnits)
        return shapeHorizontal(items, breaks, spacingUnits, lineHeightUnits, scale, justify)
    }

    // region shaping

    private class Item(
        val codePoint: Int,
        val glyph: Glyph?,
        val image: SectionImage?,
        val scale: Float,
        val color: Color?,
        val verticalAlign: VerticalAlign,
        /** The image's size in glyph units; zero for a text item. */
        val imageWidthUnits: Float = 0f,
        val imageHeightUnits: Float = 0f,
    ) {
        /** The item's advance in glyph units, already scaled by the section's `font-scale`. */
        val advance: Float
            get() = if (image != null) imageWidthUnits else (glyph?.advance ?: 0) * scale

        /** Upstream's `scale * ONE_EM` for a text section, the image's own height for an image. */
        val contentHeight: Float
            get() = if (image != null) imageHeightUnits else scale * ONE_EM

        /** The vertical counterpart of [contentHeight]. */
        val contentWidth: Float
            get() = if (image != null) imageWidthUnits else scale * ONE_EM

        val isWhitespace: Boolean get() = image == null && isWhitespace(codePoint)
    }

    /** Upstream's `getVerticalAlignFactor`. */
    private fun factorOf(verticalAlign: VerticalAlign): Float = when (verticalAlign) {
        VerticalAlign.TOP -> 0f
        VerticalAlign.CENTER -> 0.5f
        VerticalAlign.BOTTOM -> 1f
    }

    private fun flatten(
        sections: List<TextSection>,
        transform: String,
        glyphs: (List<String>, Int) -> Glyph?,
        clusters: ((List<String>, String) -> Glyph?)?,
        defaultFontStack: List<String>,
        unitsPerPixel: Float,
    ): List<Item> {
        val out = mutableListOf<Item>()
        var imageCodePoint = PUA_BEGIN
        for (section in sections) {
            val image = section.image
            if (image != null) {
                /* Upstream's `addImageSection` hardcodes `scale: 1`: a section's `font-scale` sizes
                 * text, never an image, which is sized by the sprite alone. */
                if (imageCodePoint > PUA_END) continue
                out += Item(
                    codePoint = imageCodePoint++,
                    glyph = null,
                    image = image,
                    scale = 1f,
                    color = null,
                    verticalAlign = section.verticalAlign,
                    imageWidthUnits = image.width * unitsPerPixel,
                    imageHeightUnits = image.height * unitsPerPixel,
                )
                continue
            }
            val text = when (transform) {
                TEXT_TRANSFORM_UPPERCASE -> section.text.uppercase()
                TEXT_TRANSFORM_LOWERCASE -> section.text.lowercase()
                else -> section.text
            }
            val stack = section.fontStack ?: defaultFontStack
            for (run in runsOf(text, clusters)) {
                /* A cluster is **one** item, which is what keeps line breaking from splitting a
                 * syllable -- the same single-item treatment an image section gets. It keeps its
                 * first codepoint, since that is what the break and whitespace classes are read
                 * from. */
                val clusterGlyph = run.takeIf { it.length > 1 }?.let { cluster ->
                    clusters?.invoke(stack, cluster)
                }
                if (clusterGlyph != null) {
                    out += Item(
                        codePoint = run.codePointAtCompat(0),
                        glyph = clusterGlyph,
                        image = null,
                        scale = section.scale,
                        color = section.color,
                        verticalAlign = section.verticalAlign,
                    )
                    continue
                }
                var index = 0
                while (index < run.length) {
                    val code = run.codePointAt(index)
                    index += if (code > 0xFFFF) 2 else 1
                    /* A file the style declared draws a single codepoint too, and wins over the
                     * server for it -- upstream's `_getAndCacheGlyphsPromise` asks the font faces
                     * before the range. */
                    val single = if (code > 0xFFFF) run else Char(code).toString()
                    val glyph = clusters?.invoke(stack, single) ?: glyphs(stack, code)
                    out += Item(
                        codePoint = code,
                        glyph = glyph,
                        image = null,
                        scale = section.scale,
                        color = section.color,
                        verticalAlign = section.verticalAlign,
                    )
                }
            }
        }
        return out
    }

    /**
     * The units [flatten] walks: grapheme clusters where a `font-faces` style can draw them,
     * codepoints everywhere else.
     *
     * Segmentation is skipped entirely without a cluster lookup, which is every style that has no
     * font file of its own -- upstream skips it too where nothing in the text could form a cluster.
     */
    private fun runsOf(text: String, clusters: ((List<String>, String) -> Glyph?)?): List<String> =
        if (clusters == null) listOf(text) else graphemeClusters(text)

    /** One line, measured but not yet given a pen: upstream's per-line pass in `shapeLines`. */
    private class LineMetrics(
        val items: List<Item>,
        val offsets: FloatArray,
        val baselines: FloatArray,
        val width: Float,
        val height: Float,
    )

    /**
     * The pen y of each line, in glyph units, and the block's total height.
     *
     * This is upstream's `align()` reduced to a top-left box. While every line is exactly
     * `lineHeight` tall the two shifts collapse into [firstPenY], which is what a label with no
     * image and no `font-scale` has always taken; a line grown by either takes upstream's other
     * branch, where the pen is the line's own top.
     */
    private fun penPositions(heights: FloatArray, lineHeight: Float): FloatArray {
        val maxLineHeight = heights.maxOrNull() ?: lineHeight
        val uniform = maxLineHeight == lineHeight
        val pens = FloatArray(heights.size)
        var y = if (uniform) firstPenY(lineHeight) else 0f
        for (index in heights.indices) {
            pens[index] = y
            y += if (uniform) lineHeight else heights[index]
        }
        return pens
    }

    private fun measureLine(items: List<Item>, spacing: Float, lineHeight: Float): LineMetrics {
        /* Upstream's `getMaxScale`, over the line's sections; an image section's scale is 1. */
        val maxScale = items.maxOfOrNull { it.scale } ?: 1f
        var maxImageHeight = 0f
        for (item in items) {
            if (item.image != null) maxImageHeight = max(maxImageHeight, item.imageHeightUnits)
        }
        // Upstream's `calculateLineContentSize`.
        val contentHeight = max(maxScale * ONE_EM, maxImageHeight)
        val imageOffset = max(0f, maxImageHeight - ONE_EM * maxScale)

        val offsets = FloatArray(items.size)
        val baselines = FloatArray(items.size)
        var x = 0f
        for (index in items.indices) {
            val item = items[index]
            offsets[index] = x
            baselines[index] = (contentHeight - item.contentHeight) * factorOf(item.verticalAlign)
            x += item.advance + spacing
        }
        return LineMetrics(
            items = items,
            offsets = offsets,
            baselines = baselines,
            // The trailing letter-spacing is not part of the line, as upstream's shaping is not.
            width = max(0f, x - spacing),
            height = lineHeight * maxScale + imageOffset,
        )
    }

    private fun shapeHorizontal(
        items: List<Item>,
        breaks: List<Int>,
        spacing: Float,
        lineHeight: Float,
        scale: Float,
        justify: String,
    ): ShapedLabel {
        val lineRanges = mutableListOf<IntRange>()
        var start = 0
        for (breakIndex in breaks) {
            if (breakIndex > start) lineRanges += start until breakIndex
            start = breakIndex
        }
        if (start < items.size) lineRanges += start until items.size

        val measured = lineRanges.map { range ->
            // A break consumes its whitespace, exactly as upstream's `trim` does.
            val line = items.slice(range).dropLastWhile { it.isWhitespace }.dropWhile { it.isWhitespace }
            measureLine(line, spacing, lineHeight)
        }
        if (measured.isEmpty()) return ShapedLabel(emptyList(), 0f, 0f, vertical = false)

        val heights = FloatArray(measured.size) { measured[it].height }
        val pens = penPositions(heights, lineHeight)
        val maxWidth = measured.maxOf { it.width }
        val justification = justificationOf(justify)

        val lines = measured.mapIndexed { lineIndex, line ->
            val shift = (maxWidth - line.width) * justification
            val penY = pens[lineIndex]
            val placed = mutableListOf<ShapedItem>()
            for (index in line.items.indices) {
                val item = line.items[index]
                val x = (line.offsets[index] + shift) * scale
                val y = (penY + line.baselines[index]) * scale
                if (item.image != null) {
                    placed += ShapedImage(
                        payload = item.image.payload,
                        x = x,
                        y = y,
                        width = item.image.width,
                        height = item.image.height,
                    )
                    continue
                }
                val glyph = item.glyph
                /* A codepoint the font stack has no glyph for contributes nothing at all, not even
                 * its advance -- upstream's `shapeLines` skips it entirely. */
                if (glyph != null && glyph.hasBitmap) {
                    placed += ShapedGlyph(
                        glyph = glyph,
                        x = x,
                        y = y,
                        scale = scale * item.scale,
                        color = item.color,
                    )
                }
            }
            ShapedLine(items = placed, width = line.width * scale)
        }
        return ShapedLabel(
            lines = lines,
            width = maxWidth * scale,
            height = heights.sum() * scale,
            vertical = false,
        )
    }

    /** One item per line, top to bottom: `text-writing-mode: [vertical]` for CJK labels. */
    private fun shapeVertical(items: List<Item>, scale: Float, lineHeight: Float): ShapedLabel {
        val heights = FloatArray(items.size) { index ->
            val item = items[index]
            /* A stacked image steps by its own height, where a glyph steps by the line height. */
            val imageOffset = if (item.image != null) {
                max(0f, item.imageHeightUnits - ONE_EM * item.scale)
            } else {
                0f
            }
            lineHeight * item.scale + imageOffset
        }
        val pens = penPositions(heights, lineHeight)

        val lines = mutableListOf<ShapedLine>()
        var maxWidth = 0f
        for (index in items.indices) {
            val item = items[index]
            /* Upstream's vertical `baselineOffset` is measured against the line's content *width*;
             * a line here holds one item, so the line's width is that item's and the offset is 0. */
            val y = pens[index] * scale
            val placed = when {
                item.image != null -> listOf(
                    ShapedImage(item.image.payload, 0f, y, item.image.width, item.image.height)
                )

                item.glyph != null && item.glyph.hasBitmap ->
                    listOf(ShapedGlyph(item.glyph, 0f, y, scale * item.scale, item.color))

                else -> emptyList()
            }
            val width = item.advance
            maxWidth = max(maxWidth, width)
            lines += ShapedLine(placed, width * scale)
        }
        return ShapedLabel(
            lines = lines,
            width = maxWidth * scale,
            height = heights.sum() * scale,
            vertical = true,
        )
    }

    private fun justificationOf(justify: String): Float = when (justify) {
        TEXT_JUSTIFY_LEFT -> 0f
        TEXT_JUSTIFY_RIGHT -> 1f
        // `auto` reaches here only if the caller did not resolve it against the anchor.
        TEXT_JUSTIFY_AUTO -> 0.5f
        else -> 0.5f
    }

    // endregion

    // region line breaking -- upstream `determineLineBreaks` and its helpers

    private class PotentialBreak(
        val index: Int,
        val x: Float,
        val priorBreak: PotentialBreak?,
        val badness: Float,
    )

    /**
     * Where the label should wrap, as indices into [items].
     *
     * Upstream does not wrap greedily at the first overflow: it divides the text into as many lines
     * as it needs and then picks the breaks that make those lines *evenly* long, penalising a break
     * that lands somewhere awkward. That is why a two-line label under MapLibre reads as two halves
     * rather than one full line and a stub.
     */
    private fun determineLineBreaks(items: List<Item>, spacing: Float, maxWidth: Float): List<Int> {
        if (maxWidth <= 0f || items.isEmpty()) return emptyList()

        val targetWidth = averageLineWidth(items, spacing, maxWidth)
        val potentialBreaks = mutableListOf<PotentialBreak>()
        var x = 0f

        /* Upstream only penalises an ideographic break when the *server* suggested breakpoints with
         * zero-width spaces -- the penalty exists to prefer those, not to discourage wrapping CJK,
         * which has no spaces to wrap at. */
        val hasServerSuggestedBreakpoints = items.any { it.image == null && it.codePoint == 0x200b }

        for (index in items.indices) {
            val item = items[index]
            x += item.advance + spacing
            if (index == items.lastIndex) continue
            // An image is one private-use codepoint: neither breakable nor ideographic.
            if (item.image != null) continue
            val code = item.codePoint
            val ideographic = allowsIdeographicBreak(code)
            if (isBreakable(code) || ideographic) {
                potentialBreaks += evaluateBreak(
                    breakIndex = index + 1,
                    breakX = x,
                    targetWidth = targetWidth,
                    potentialBreaks = potentialBreaks,
                    penalty = penaltyOf(code, ideographic && hasServerSuggestedBreakpoints),
                    isLastBreak = false,
                )
            }
        }

        val best = evaluateBreak(
            breakIndex = items.size,
            breakX = x,
            targetWidth = targetWidth,
            potentialBreaks = potentialBreaks,
            penalty = 0f,
            isLastBreak = true,
        )
        return leastBadBreaks(best)
    }

    private fun averageLineWidth(items: List<Item>, spacing: Float, maxWidth: Float): Float {
        var total = 0f
        for (item in items) total += item.advance + spacing
        val lineCount = max(1, ceil(total / maxWidth).toInt())
        return total / lineCount
    }

    private fun evaluateBreak(
        breakIndex: Int,
        breakX: Float,
        targetWidth: Float,
        potentialBreaks: List<PotentialBreak>,
        penalty: Float,
        isLastBreak: Boolean,
    ): PotentialBreak {
        var bestPriorBreak: PotentialBreak? = null
        var bestBadness = badness(breakX, targetWidth, penalty, isLastBreak)
        for (potentialBreak in potentialBreaks) {
            val lineWidth = breakX - potentialBreak.x
            val breakBadness = badness(lineWidth, targetWidth, penalty, isLastBreak) + potentialBreak.badness
            if (breakBadness <= bestBadness) {
                bestPriorBreak = potentialBreak
                bestBadness = breakBadness
            }
        }
        return PotentialBreak(breakIndex, breakX, bestPriorBreak, bestBadness)
    }

    /** Upstream's `calculateBadness`: squared raggedness, with the last line allowed to be short. */
    private fun badness(lineWidth: Float, targetWidth: Float, penalty: Float, isLastBreak: Boolean): Float {
        val raggedness = (lineWidth - targetWidth) * (lineWidth - targetWidth)
        if (isLastBreak) {
            return if (lineWidth < targetWidth) raggedness / 2f else raggedness * 2f
        }
        return raggedness + abs(penalty) * penalty
    }

    /** Upstream's `calculatePenalty`. A newline is all but free; a bracket is a poor place to break. */
    private fun penaltyOf(codePoint: Int, ideographicBreak: Boolean): Float {
        var penalty = 0f
        if (codePoint == 0x0a) penalty -= 10000f
        if (codePoint == 0x28 || codePoint == 0xff08) penalty += 50f
        if (codePoint == 0x29 || codePoint == 0xff09) penalty += 50f
        if (ideographicBreak) penalty += 150f
        return penalty
    }

    private fun leastBadBreaks(lastBreak: PotentialBreak?): List<Int> {
        val breaks = mutableListOf<Int>()
        var current = lastBreak
        while (current != null) {
            breaks += current.index
            current = current.priorBreak
        }
        breaks.reverse()
        // The final entry is the end of the text, not a break within it.
        return breaks.dropLast(1)
    }

    /** Upstream's `breakable` table. */
    private fun isBreakable(codePoint: Int): Boolean = when (codePoint) {
        0x0a, // \n
        0x20, // space
        0x26, // &
        0x2b, // +
        0x2d, // -
        0x2f, // /
        0xad, // soft hyphen
        0xb7, // ·
        0x200b, // zero-width space
        0x2010, // hyphen
        0x2013, // en dash
        0x2027, // interpunct
        -> true

        else -> false
    }

    private fun isWhitespace(codePoint: Int): Boolean =
        codePoint == 0x20 || codePoint == 0x0a || codePoint == 0x09 || codePoint == 0x200b

    /**
     * Whether a line may break *after* this character without a space -- CJK and friends.
     *
     * Upstream's `charAllowsIdeographicBreaking`, narrowed to the blocks a glyph server actually
     * serves: CJK ideographs and the two kana blocks, plus the CJK symbols and full-width forms.
     */
    private fun allowsIdeographicBreak(codePoint: Int): Boolean = when (codePoint) {
        in 0x3000..0x303f, // CJK symbols and punctuation
        in 0x3040..0x309f, // Hiragana
        in 0x30a0..0x30ff, // Katakana
        in 0x3400..0x4dbf, // CJK extension A
        in 0x4e00..0x9fff, // CJK unified ideographs
        in 0xf900..0xfaff, // CJK compatibility ideographs
        in 0xff00..0xffef, // Halfwidth and fullwidth forms
        -> true

        else -> false
    }

    /** Whether a character may be set vertically, i.e. is one of the blocks above. */
    private fun allowsVertical(codePoint: Int): Boolean =
        allowsIdeographicBreak(codePoint) || isWhitespace(codePoint)

    /**
     * Whether [text] may be set vertically at all -- upstream's `allowsVerticalWritingMode`
     * (`src/util/script_detection.ts`).
     *
     * **Divergence:** *every* codepoint has to be one this port can stack, where upstream asks
     * whether *any* has upright vertical orientation. Upstream can afford the loose test because its
     * vertical shaping rotates the rest ninety degrees -- `charHasUprightVerticalOrientation`
     * against the rotated set, then a rotated quad in `quads.ts` -- and there is no glyph rotation
     * here at all, so a mixed label would stack Latin letters one per line. Being stricter costs
     * only the vertical *candidate*: the horizontal shaping is always built, and placement falls
     * back to it.
     *
     * An image section's private-use codepoint stacks like an ideograph, as it did when this test
     * lived inside [shape] as `it.image != null || allowsVertical(...)`.
     */
    fun allowsVerticalWritingMode(text: String): Boolean {
        if (text.isEmpty()) return false
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += if (code > 0xFFFF) 2 else 1
            if (code in PUA_BEGIN..PUA_END) continue
            if (!allowsVertical(code)) return false
        }
        return true
    }

    // endregion
}

/** `String.codePointAt`, which Kotlin common does not have. */
private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}
