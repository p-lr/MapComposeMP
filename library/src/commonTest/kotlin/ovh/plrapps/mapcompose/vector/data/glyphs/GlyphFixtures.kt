package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * Builders for `glyphs.pbf` range files.
 *
 * The same approach `MvtFixtures` takes for vector tiles: the wire format is built by hand so the
 * encoding a test exercises is visible in the test, rather than hidden in a binary fixture the
 * upstream package does not publish anyway.
 */
internal object GlyphPbfFixtures {

    fun varint(value: Long): ByteArray {
        var remaining = value
        val out = mutableListOf<Byte>()
        while (true) {
            val byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                out += byte.toByte()
                return out.toByteArray()
            }
            out += (byte or 0x80).toByte()
        }
    }

    fun zigZag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    fun varintField(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

    fun bytesField(field: Int, value: ByteArray): ByteArray =
        tag(field, 2) + varint(value.size.toLong()) + value

    fun stringField(field: Int, value: String): ByteArray = bytesField(field, value.encodeToByteArray())

    /** A `glyph` message. [bitmap] must be `(width + 6) * (height + 6)` bytes when present. */
    fun glyph(
        id: Int,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        advance: Int,
        bitmap: ByteArray? = null,
    ): ByteArray =
        varintField(1, id.toLong()) +
            (bitmap?.let { bytesField(2, it) } ?: ByteArray(0)) +
            varintField(3, width.toLong()) +
            varintField(4, height.toLong()) +
            varintField(5, zigZag(left)) +
            varintField(6, zigZag(top)) +
            varintField(7, advance.toLong())

    fun fontStack(name: String, range: String, glyphs: List<ByteArray>): ByteArray =
        stringField(1, name) + stringField(2, range) +
            glyphs.fold(ByteArray(0)) { acc, glyph -> acc + bytesField(3, glyph) }

    fun glyphsFile(vararg stacks: ByteArray): ByteArray =
        stacks.fold(ByteArray(0)) { acc, stack -> acc + bytesField(1, stack) }

    /** A square block of full-strength distance, i.e. a glyph that is solid ink. */
    fun solidBitmap(width: Int, height: Int): ByteArray =
        ByteArray((width + 2 * GLYPH_BORDER) * (height + 2 * GLYPH_BORDER)) { 0xFF.toByte() }
}
