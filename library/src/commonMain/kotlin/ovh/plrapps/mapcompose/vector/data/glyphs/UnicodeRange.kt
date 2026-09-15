package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * An inclusive range of Unicode codepoints, parsed out of a `unicode-range` entry.
 *
 * A port of the `UnicodeRange` type and its parser in maplibre-gl-js
 * `src/render/font_face_manager.ts`.
 */
class UnicodeRange(val start: Int, val end: Int) {
    fun covers(codePoint: Int): Boolean = codePoint in start..end

    companion object {
        /** The largest codepoint there is; every parsed range is clamped to it. */
        const val MAX_CODE_POINT = 0x10FFFF

        /** What a font file covers when the style narrows it down with no `unicode-range`. */
        val DEFAULT = UnicodeRange(0, MAX_CODE_POINT)

        /**
         * Parses one `unicode-range` entry, in the CSS grammar the style specification borrows:
         * `U+A5`, `U+0-10FFFF` or `U+4??`.
         *
         * Returns `null` for an entry that is not a range this can make sense of, which the caller
         * reports and skips -- upstream's `warnOnce` in `_declareFontFace`.
         */
        fun parse(value: String): UnicodeRange? {
            val text = value.trim()
            if (text.length < 3) return null
            if (!text.startsWith("u+", ignoreCase = true)) return null
            val body = text.substring(2)

            val questionMarks = body.takeLastWhile { it == '?' }.length
            if (questionMarks > 0) {
                val prefix = body.dropLast(questionMarks)
                if (prefix.length + questionMarks > 6) return null
                if (!prefix.all { it.isHexDigit() }) return null
                val start = "$prefix${"0".repeat(questionMarks)}".toIntOrNull(16) ?: return null
                val end = "$prefix${"f".repeat(questionMarks)}".toIntOrNull(16) ?: return null
                return clamp(start, end)
            }

            val parts = body.split('-')
            if (parts.size > 2) return null
            val first = parts[0]
            if (first.length !in 1..6 || !first.all { it.isHexDigit() }) return null
            val start = first.toIntOrNull(16) ?: return null
            if (parts.size == 1) return clamp(start, start)
            val second = parts[1]
            if (second.length !in 1..6 || !second.all { it.isHexDigit() }) return null
            val end = second.toIntOrNull(16) ?: return null
            return clamp(start, end)
        }

        private fun clamp(start: Int, end: Int): UnicodeRange? {
            if (start > end || start > MAX_CODE_POINT) return null
            return UnicodeRange(start, minOf(end, MAX_CODE_POINT))
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** Whether any of these ranges covers [codePoint]. */
fun List<UnicodeRange>.covers(codePoint: Int): Boolean = any { it.covers(codePoint) }
