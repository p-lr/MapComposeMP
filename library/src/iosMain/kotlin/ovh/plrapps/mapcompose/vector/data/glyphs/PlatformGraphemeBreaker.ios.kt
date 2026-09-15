package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMakeRange
import platform.Foundation.NSString
import platform.Foundation.NSStringEnumerationByComposedCharacterSequences
import platform.Foundation.enumerateSubstringsInRange

/**
 * Foundation's *composed character sequences*, the closest thing `NSString` offers to a grapheme
 * cluster.
 *
 * It is not quite UAX #29: a composed character sequence is a base plus its combining marks, so an
 * emoji ZWJ sequence or a regional indicator pair comes apart where `Intl.Segmenter` and
 * `BreakIterator` keep it whole. What the font-faces path is after -- a letter drawn with its marks
 * -- is exactly what this does give, and `GraphemeBreakerTest` asserts only that much.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun graphemeClusters(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val string = text as NSString
    val out = mutableListOf<String>()
    string.enumerateSubstringsInRange(
        range = NSMakeRange(0uL, string.length),
        options = NSStringEnumerationByComposedCharacterSequences,
    ) { substring, _, _, _ ->
        if (substring != null) out += substring
    }
    return out
}
