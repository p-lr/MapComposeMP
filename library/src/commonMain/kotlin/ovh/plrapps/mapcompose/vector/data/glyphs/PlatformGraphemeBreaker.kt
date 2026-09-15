package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * Splits [text] into grapheme clusters -- upstream's `toGraphemes` (`src/util/graphemes.ts`), which
 * is `Intl.Segmenter` at `grapheme` granularity.
 *
 * A codepoint is not a unit of writing: a Devanagari or Khmer syllable is several codepoints that
 * come apart when drawn one at a time and hold together when drawn as one cluster. Only the
 * `font-faces` path asks for this -- a glyph server serves codepoints -- so a style declaring no
 * font file never segments anything.
 *
 * Every target reaches its platform's own segmentation, the same way [compareLocalized] reaches its
 * collation: `java.text.BreakIterator` on Android and desktop, `NSString`'s composed character
 * sequences on iOS, `Intl.Segmenter` on wasm. They disagree at the edges -- Foundation's composed
 * character sequences are not extended grapheme clusters, and emoji ZWJ sequences differ -- so
 * `GraphemeBreakerTest` asserts only what all four agree on. Upstream's `canCombineGraphemes`
 * correction, which re-joins what CLDR's cursor rules split, is not ported: it is a generated table
 * of Indic and Hangul properties, and a cluster split one codepoint too finely draws as it does
 * today.
 *
 * @see [ovh.plrapps.mapcompose.vector.spec.style.expression.types.compareLocalized]
 */
internal expect fun graphemeClusters(text: String): List<String>

/** [text] split into codepoints, which is what a target with no segmenter falls back to. */
internal fun codePointStrings(text: String): List<String> {
    val out = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val high = text[index]
        val length = if (high.isHighSurrogate() && index + 1 < text.length &&
            text[index + 1].isLowSurrogate()
        ) 2 else 1
        out += text.substring(index, index + length)
        index += length
    }
    return out
}

/** `String.codePointAt`, which Kotlin common does not have. */
internal fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}
