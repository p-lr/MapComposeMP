package ovh.plrapps.mapcompose.vector.data.glyphs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.utils.LruCache

/**
 * Fetches and caches the SDF glyphs a style's labels need.
 *
 * A style's `glyphs` is a URL template with `{fontstack}` and `{range}` placeholders, e.g.
 * `https://example.com/fonts/{fontstack}/{range}.pbf`. Ranges are 256 codepoints wide, so a label
 * pulls one file per font stack per range it touches, and every tile after the first is served from
 * the cache. That is upstream's `GlyphManager` behaviour, minus its per-font `TinySDF` fallback for
 * CJK, which is left out here -- a codepoint the server has no glyph for is simply not drawn.
 *
 * Nothing here throws: a range that fails to load is cached as empty, so a font the server does not
 * have costs one request rather than one per tile forever.
 */
class GlyphManager(
    private val urlTemplate: String?,
    private val loadResource: suspend (String) -> RawSource?,
    maxRanges: Int = 128,
) {
    private val cache = LruCache<String, Map<Int, Glyph>>(maxSize = maxRanges)
    private val mutex = Mutex()

    /** Whether the style declared a `glyphs` URL at all. */
    val isConfigured: Boolean get() = !urlTemplate.isNullOrEmpty()

    /**
     * The glyphs of [fontStack] covering every codepoint in [text].
     *
     * [fontStack] is the layer's `text-font` in order; it is joined with commas, which is how the
     * template's `{fontstack}` is defined -- the server composes the stack and answers with one
     * merged range.
     */
    suspend fun glyphsFor(fontStack: List<String>, text: String): Map<Int, Glyph> {
        if (!isConfigured || fontStack.isEmpty() || text.isEmpty()) return emptyMap()

        val stack = fontStack.joinToString(",")
        val ranges = rangesOf(text)
        if (ranges.isEmpty()) return emptyMap()

        val out = mutableMapOf<Int, Glyph>()
        for (range in ranges) {
            out += rangeGlyphs(stack, range)
        }
        return out
    }

    private suspend fun rangeGlyphs(stack: String, range: Int): Map<Int, Glyph> {
        val key = "$stack/$range"
        mutex.withLock { cache.get(key) }?.let { return it }

        val url = urlFor(stack, range) ?: return emptyMap()
        val bytes = runCatching {
            withContext(IODispatcher) { loadResource(url)?.buffered()?.readByteArray() }
        }.getOrNull()

        val glyphs = bytes
            ?.let { GlyphPbf.decode(it) }
            ?.fold(mutableMapOf<Int, Glyph>()) { acc, stackGlyphs -> acc.apply { putAll(stackGlyphs.glyphs) } }
            ?: emptyMap()

        mutex.withLock { cache.put(key, glyphs) }
        return glyphs
    }

    private fun urlFor(stack: String, range: Int): String? {
        val template = urlTemplate ?: return null
        val start = range * RANGE_SIZE
        val end = start + RANGE_SIZE - 1
        return template
            .replace("{fontstack}", encodeFontStack(stack))
            .replace("{range}", "$start-$end")
    }

    companion object {
        /** Codepoints per range file. Upstream's fixed 256. */
        const val RANGE_SIZE = 256

        /** The highest range a glyph server serves: the Basic Multilingual Plane. */
        const val MAX_RANGE = 255

        /**
         * The ranges [text] needs, ascending and without duplicates.
         *
         * Codepoints above the BMP are skipped: glyph servers only publish ranges `0-255` through
         * `65280-65535`, so asking for one beyond that is a guaranteed 404. Surrogate pairs are read
         * as whole codepoints so an emoji does not produce two spurious ranges.
         */
        fun rangesOf(text: String): List<Int> {
            // A plain set, sorted at the end: `sortedSetOf` is JVM-only.
            val ranges = mutableSetOf<Int>()
            var index = 0
            while (index < text.length) {
                val code = text.codePointAtCompat(index)
                index += if (code > 0xFFFF) 2 else 1
                val range = code / RANGE_SIZE
                if (range in 0..MAX_RANGE) ranges += range
            }
            return ranges.sorted()
        }

        /**
         * Percent-encodes a font stack for the URL's `{fontstack}` slot.
         *
         * Font names contain spaces ("Open Sans Regular"), and the comma joining a stack must stay
         * a comma -- upstream encodes the stack the same way.
         */
        fun encodeFontStack(stack: String): String = buildString {
            for (char in stack) {
                when {
                    char.isLetterOrDigit() || char in "-_.~,/" -> append(char)
                    char == ' ' -> append("%20")
                    else -> {
                        for (byte in char.toString().encodeToByteArray()) {
                            append('%')
                            append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                        }
                    }
                }
            }
        }
    }
}

/** `String.codePointAt`, which Kotlin common does not have. */
private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}
