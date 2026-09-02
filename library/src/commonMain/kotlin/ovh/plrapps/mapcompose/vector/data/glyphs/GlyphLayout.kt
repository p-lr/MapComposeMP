package ovh.plrapps.mapcompose.vector.data.glyphs

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_AUTO
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_LEFT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_RIGHT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_LOWERCASE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_UPPERCASE
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_VERTICAL
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
 * upstream's balanced line breaking, `text-justify`, and the vertical half of `text-writing-mode`.
 *
 * **Divergences.** There is no bidirectional reordering: upstream hands right-to-left text to an
 * optional `rtl-text-plugin` and this has no equivalent, so Arabic and Hebrew shape in logical
 * order. Arabic contextual forms are likewise left to the font. And a codepoint the glyph server has
 * no glyph for is dropped rather than falling back to a locally rendered `TinySDF`.
 */

/** One run of a label that shares a font, size and colour -- a `["format", ...]` section. */
class TextSection(
    val text: String,
    val scale: Float = 1f,
    val fontStack: List<String>? = null,
    val color: Color? = null,
)

/** One glyph, placed. [x] and [y] are the pen position on its line's baseline, in layout pixels. */
class ShapedGlyph(
    val glyph: Glyph,
    val x: Float,
    val y: Float,
    /** Layout pixels per glyph unit, including the section's own `font-scale`. */
    val scale: Float,
    val color: Color?,
) {
    /** The glyph's ink box in layout pixels, relative to the label's top-left. */
    val inkLeft: Float get() = x + glyph.left * scale
    val inkTop: Float get() = y - glyph.top * scale
    val inkWidth: Float get() = glyph.width * scale
    val inkHeight: Float get() = glyph.height * scale
}

class ShapedLine(val glyphs: List<ShapedGlyph>, val width: Float)

/**
 * A shaped label.
 *
 * [width] and [height] are the box the label occupies in layout pixels, with the glyphs positioned
 * relative to its top-left corner. Anchoring that box to a point is the painter's job, not this
 * one's -- see the `text-anchor` handling in `SymbolLayerPainter`.
 */
class ShapedLabel(
    val lines: List<ShapedLine>,
    val width: Float,
    val height: Float,
    val vertical: Boolean,
) {
    val glyphs: List<ShapedGlyph> get() = lines.flatMap { it.glyphs }
    val isEmpty: Boolean get() = lines.all { it.glyphs.isEmpty() }
}

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
     */
    private const val SHAPING_DEFAULT_OFFSET = -17f

    /** The pen position of the first line within the label's box, in glyph units. */
    private fun firstPenY(lineHeight: Float): Float = 0.5f * lineHeight + SHAPING_DEFAULT_OFFSET

    /**
     * Shapes [sections] into positioned glyphs.
     *
     * @param glyphs looks a codepoint up in a font stack. The section's own `text-font` wins over
     * [defaultFontStack], as `["format", ..., {"text-font": [...]}]` is defined to.
     * @param fontSize `text-size` in layout pixels.
     * @param letterSpacing `text-letter-spacing` in ems.
     * @param lineHeight `text-line-height` in ems.
     * @param maxWidth `text-max-width` in ems; zero or negative disables wrapping.
     * @param justify `text-justify`; `auto` is resolved by the caller and treated as `center` here.
     * @param writingMode `text-writing-mode`, in the style's preference order.
     * @param transform `text-transform`.
     */
    fun shape(
        sections: List<TextSection>,
        glyphs: (List<String>, Int) -> Glyph?,
        defaultFontStack: List<String>,
        fontSize: Float,
        letterSpacing: Float,
        lineHeight: Float,
        maxWidth: Float,
        justify: String,
        writingMode: List<String>?,
        transform: String,
    ): ShapedLabel {
        val scale = fontSize / ONE_EM
        val spacingUnits = letterSpacing * ONE_EM
        val lineHeightUnits = lineHeight * ONE_EM
        val maxWidthUnits = maxWidth * ONE_EM

        val chars = flatten(sections, transform, glyphs, defaultFontStack)
        if (chars.isEmpty()) return ShapedLabel(emptyList(), 0f, 0f, vertical = false)

        val vertical = writingMode?.contains(WRITING_MODE_VERTICAL) == true && chars.all { allowsVertical(it.codePoint) }
        if (vertical) return shapeVertical(chars, scale, lineHeightUnits)

        val breaks = determineLineBreaks(chars, spacingUnits, maxWidthUnits)
        return shapeHorizontal(chars, breaks, spacingUnits, lineHeightUnits, scale, justify)
    }

    // region shaping

    private class Char(
        val codePoint: Int,
        val glyph: Glyph?,
        val scale: Float,
        val color: Color?,
    ) {
        /** The glyph's advance in glyph units, already scaled by the section's `font-scale`. */
        val advance: Float get() = (glyph?.advance ?: 0) * scale
    }

    private fun flatten(
        sections: List<TextSection>,
        transform: String,
        glyphs: (List<String>, Int) -> Glyph?,
        defaultFontStack: List<String>,
    ): List<Char> {
        val out = mutableListOf<Char>()
        for (section in sections) {
            val text = when (transform) {
                TEXT_TRANSFORM_UPPERCASE -> section.text.uppercase()
                TEXT_TRANSFORM_LOWERCASE -> section.text.lowercase()
                else -> section.text
            }
            val stack = section.fontStack ?: defaultFontStack
            var index = 0
            while (index < text.length) {
                val code = text.codePointAt(index)
                index += if (code > 0xFFFF) 2 else 1
                out += Char(
                    codePoint = code,
                    glyph = glyphs(stack, code),
                    scale = section.scale,
                    color = section.color,
                )
            }
        }
        return out
    }

    private fun shapeHorizontal(
        chars: List<Char>,
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
        if (start < chars.size) lineRanges += start until chars.size

        val laidOut = mutableListOf<Pair<MutableList<ShapedGlyph>, Float>>()
        var y = firstPenY(lineHeight)
        for (range in lineRanges) {
            // A break consumes its whitespace, exactly as upstream's `trim` does.
            val line = chars.slice(range).dropLastWhile { isWhitespace(it.codePoint) }
                .dropWhile { isWhitespace(it.codePoint) }
            var x = 0f
            val placed = mutableListOf<ShapedGlyph>()
            for (char in line) {
                val glyph = char.glyph
                /* A codepoint the font stack has no glyph for contributes nothing at all, not even
                 * its advance -- upstream's `shapeLines` skips it entirely. */
                if (glyph != null && glyph.hasBitmap) {
                    placed += ShapedGlyph(
                        glyph = glyph,
                        x = x * scale,
                        y = y * scale,
                        scale = scale * char.scale,
                        color = char.color,
                    )
                }
                x += char.advance + spacing
            }
            // The trailing letter-spacing is not part of the line, as upstream's shaping is not.
            val width = max(0f, x - spacing)
            laidOut += placed to width
            y += lineHeight
        }

        if (laidOut.isEmpty()) return ShapedLabel(emptyList(), 0f, 0f, vertical = false)

        val maxWidth = laidOut.maxOf { it.second }
        val justification = justificationOf(justify)

        val lines = laidOut.map { (placed, width) ->
            val shift = (maxWidth - width) * justification * scale
            ShapedLine(
                glyphs = if (shift == 0f) placed else placed.map {
                    ShapedGlyph(it.glyph, it.x + shift, it.y, it.scale, it.color)
                },
                width = width * scale,
            )
        }
        return ShapedLabel(
            lines = lines,
            width = maxWidth * scale,
            height = lines.size * lineHeight * scale,
            vertical = false,
        )
    }

    /** One glyph per line, top to bottom: `text-writing-mode: [vertical]` for CJK labels. */
    private fun shapeVertical(chars: List<Char>, scale: Float, lineHeight: Float): ShapedLabel {
        var y = firstPenY(lineHeight)
        val lines = mutableListOf<ShapedLine>()
        var maxWidth = 0f
        for (char in chars) {
            val glyph = char.glyph
            val placed = if (glyph != null && glyph.hasBitmap) {
                listOf(ShapedGlyph(glyph, 0f, y * scale, scale * char.scale, char.color))
            } else {
                emptyList()
            }
            val width = char.advance
            maxWidth = max(maxWidth, width)
            lines += ShapedLine(placed, width * scale)
            y += lineHeight
        }
        return ShapedLabel(
            lines = lines,
            width = maxWidth * scale,
            height = lines.size * lineHeight * scale,
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
     * Where the label should wrap, as indices into [chars].
     *
     * Upstream does not wrap greedily at the first overflow: it divides the text into as many lines
     * as it needs and then picks the breaks that make those lines *evenly* long, penalising a break
     * that lands somewhere awkward. That is why a two-line label under MapLibre reads as two halves
     * rather than one full line and a stub.
     */
    private fun determineLineBreaks(chars: List<Char>, spacing: Float, maxWidth: Float): List<Int> {
        if (maxWidth <= 0f || chars.isEmpty()) return emptyList()

        val targetWidth = averageLineWidth(chars, spacing, maxWidth)
        val potentialBreaks = mutableListOf<PotentialBreak>()
        var x = 0f

        /* Upstream only penalises an ideographic break when the *server* suggested breakpoints with
         * zero-width spaces -- the penalty exists to prefer those, not to discourage wrapping CJK,
         * which has no spaces to wrap at. */
        val hasServerSuggestedBreakpoints = chars.any { it.codePoint == 0x200b }

        for (index in chars.indices) {
            val char = chars[index]
            x += char.advance + spacing
            if (index == chars.lastIndex) continue
            val code = char.codePoint
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
            breakIndex = chars.size,
            breakX = x,
            targetWidth = targetWidth,
            potentialBreaks = potentialBreaks,
            penalty = 0f,
            isLastBreak = true,
        )
        return leastBadBreaks(best)
    }

    private fun averageLineWidth(chars: List<Char>, spacing: Float, maxWidth: Float): Float {
        var total = 0f
        for (char in chars) total += char.advance + spacing
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
