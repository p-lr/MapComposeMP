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
 * The glyphs a label needs: the ones a style's `font-faces` files draw, and the ones its `glyphs`
 * server serves.
 *
 * [byGrapheme] holds what was drawn locally, keyed by the grapheme cluster it was drawn from -- a
 * whole syllable where a script builds one out of several codepoints. [byCodePoint] holds what the
 * server served, keyed as the range files are. A shaper asks the first and falls back to the second,
 * which is upstream's order in `GlyphManager._getAndCacheGlyphsPromise`.
 */
class GlyphSet(
    val byCodePoint: Map<Int, Glyph>,
    val byGrapheme: Map<String, Glyph> = emptyMap(),
) {
    val isEmpty: Boolean get() = byCodePoint.isEmpty() && byGrapheme.isEmpty()

    companion object {
        val EMPTY = GlyphSet(emptyMap(), emptyMap())
    }
}

/**
 * Fetches and caches the SDF glyphs a style's labels need.
 *
 * A style's `glyphs` is a URL template with `{fontstack}` and `{range}` placeholders, e.g.
 * `https://example.com/fonts/{fontstack}/{range}.pbf`. Ranges are 256 codepoints wide, so a label
 * pulls one file per font stack per range it touches, and every tile after the first is served from
 * the cache. That is upstream's `GlyphManager` behaviour, minus its per-font `TinySDF` fallback for
 * CJK, which is left out here -- a codepoint nothing covers is simply not drawn.
 *
 * A style declaring root `font-faces` gets the other half of upstream's resolution: a codepoint a
 * declared font file covers is **drawn locally**, from that file, and never asked of the server. The
 * unit drawn is a grapheme cluster rather than a codepoint, so a letter reaches the platform's text
 * engine with its marks -- which is the whole point of the property, and is why such a style renders
 * Devanagari or Khmer correctly where a glyph server that does not publish those ranges renders
 * nothing. Segmentation costs nothing for a style without the property: it is skipped entirely.
 *
 * Nothing here throws: a range that fails to load is cached as empty, so a font the server does not
 * have costs one request rather than one per tile forever.
 */
class GlyphManager(
    private val urlTemplate: String?,
    private val loadResource: suspend (String) -> RawSource?,
    private val fontFaces: FontFaceManager? = null,
    private val localGlyphs: LocalGlyphSource? = null,
    maxRanges: Int = 128,
    maxLocalGlyphs: Int = MAX_LOCAL_GLYPHS,
) {
    private val cache = LruCache<String, Map<Int, Glyph>>(maxSize = maxRanges)
    private val drawn = LruCache<String, Glyph>(maxSize = maxLocalGlyphs)
    private val mutex = Mutex()

    /** Whether the style declared a `glyphs` URL at all. */
    private val hasServer: Boolean get() = !urlTemplate.isNullOrEmpty()

    /** The font files the style declared, or null when it declared none this can draw with. */
    private val faces: FontFaceManager?
        get() = fontFaces?.takeIf { it.hasFontFaces && localGlyphs != null }

    /**
     * Whether anything here can produce a glyph: a `glyphs` URL, declared font files, or both.
     *
     * A style with font files and no server still takes the glyph path rather than the Compose
     * fallback -- it has real fonts to draw with, which is what the fallback exists for the absence
     * of.
     */
    val isConfigured: Boolean get() = hasServer || faces != null

    /**
     * The glyphs of [fontStack] covering every grapheme in [text].
     *
     * [fontStack] is the layer's `text-font` in order; it is joined with commas for the server,
     * which is how the template's `{fontstack}` is defined -- the server composes the stack and
     * answers with one merged range -- and walked name by name for the font files, which is how
     * upstream's `FontFaceManager.getFontFamily` walks it.
     */
    suspend fun glyphsFor(fontStack: List<String>, text: String): GlyphSet {
        if (!isConfigured || fontStack.isEmpty() || text.isEmpty()) return GlyphSet.EMPTY

        val declared = faces ?: return GlyphSet(serverGlyphs(fontStack, text))

        val byGrapheme = mutableMapOf<String, Glyph>()
        val forServer = StringBuilder()
        for (grapheme in graphemeClusters(text).distinct()) {
            if (grapheme.isEmpty()) continue
            val face = declared.familyFor(fontStack, grapheme.codePointAtCompat(0))
            val local = face?.let { localGlyph(it, grapheme) }
            if (local != null) {
                byGrapheme[grapheme] = local
                continue
            }
            /* Whatever no file drew is left to the server: a codepoint no file covers, one whose
             * file the platform's text stack cannot read -- the specification asks for an
             * unsupported font to be ignored -- and a whole cluster, codepoint by codepoint.
             *
             * That last one is a divergence: upstream caches a `null` for such a cluster and draws
             * nothing, since only a declared file can draw one whole. Decomposing it does take a
             * letter apart from its marks, but the alternative is that adding a `font-faces` block
             * for one script silently blanks every accented word of another, which the server was
             * serving perfectly well before. */
            forServer.append(grapheme)
        }

        val byCodePoint = if (forServer.isEmpty()) {
            emptyMap()
        } else {
            serverGlyphs(fontStack, forServer.toString())
        }
        return GlyphSet(byCodePoint = byCodePoint, byGrapheme = byGrapheme)
    }

    /**
     * One grapheme drawn from a declared file, cached by the file it was drawn with.
     *
     * A grapheme the file could **not** draw is cached too, as [MISSING]: a font the platform cannot
     * read fails once per grapheme rather than once per label, which is the rule a failed range
     * already follows. Upstream caches the same `null` in its `entry.glyphs`.
     */
    private suspend fun localGlyph(face: LoadedFontFace, grapheme: String): Glyph? {
        val source = localGlyphs ?: return null
        val key = "${face.identity}|$grapheme"
        mutex.withLock { drawn.get(key) }?.let { return it.takeIf { cached -> cached !== MISSING } }

        val glyph = source.rasterize(face.family, grapheme)
        mutex.withLock { drawn.put(key, glyph ?: MISSING) }
        return glyph
    }

    /** Every glyph the server has for [text], one request per 256-codepoint range. */
    private suspend fun serverGlyphs(fontStack: List<String>, text: String): Map<Int, Glyph> {
        if (!hasServer) return emptyMap()
        val ranges = rangesOf(text)
        if (ranges.isEmpty()) return emptyMap()

        val stack = fontStack.joinToString(",")
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
        /** Stands for "this file cannot draw this grapheme", so that it is not tried again. */
        private val MISSING = Glyph(
            id = 0, bitmap = null, width = 0, height = 0, left = 0, top = 0, advance = 0,
        )

        /** Codepoints per range file. Upstream's fixed 256. */
        const val RANGE_SIZE = 256

        /** The highest range a glyph server serves: the Basic Multilingual Plane. */
        const val MAX_RANGE = 255

        /**
         * How many locally drawn graphemes to keep.
         *
         * A cluster is a whole syllable, so a script that builds them has far more of them than it
         * has codepoints -- and each costs a text layout and a distance transform, which is why this
         * sits well above the range cache.
         */
        const val MAX_LOCAL_GLYPHS = 2048

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
