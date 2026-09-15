package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * `Intl.Segmenter` at `grapheme` granularity, which is upstream's own segmenter.
 *
 * A browser without it -- the API is recent -- falls back to codepoints, which is what upstream's
 * `toGraphemes` does when `'Segmenter' in Intl` is false. The segments come back as one string
 * joined by a separator, because handing a `JsArray` of strings across the wasm boundary costs an
 * external declaration per element; the separator is a C0 control that no label carries and that
 * the shaper would drop in any case.
 */
internal actual fun graphemeClusters(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    if (!hasSegmenter()) return codePointStrings(text)
    return segmentGraphemes(text, SEPARATOR).split(SEPARATOR)
}

/** `U+001F`, the unit separator. */
private val SEPARATOR = Char(0x1F).toString()

private fun hasSegmenter(): Boolean = js("typeof Intl !== 'undefined' && 'Segmenter' in Intl")

private fun segmentGraphemes(text: String, separator: String): String = js(
    """{
        var segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});
        var parts = [];
        for (var part of segmenter.segment(text)) parts.push(part.segment);
        return parts.join(separator);
    }"""
)
