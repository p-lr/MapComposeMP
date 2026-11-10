package ovh.plrapps.mapcompose.vector.data.glyphs

import java.text.BreakIterator

/**
 * `java.text.BreakIterator.getCharacterInstance()`, which is the JDK's and Android's UAX #29
 * grapheme cluster boundary.
 *
 * A new iterator per call: `BreakIterator` carries the text it is walking, and this is reached from
 * the whole tile-worker pool -- the same rule `compareLocalized` follows for `java.text.Collator`.
 */
internal actual fun graphemeClusters(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val out = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return out
}
