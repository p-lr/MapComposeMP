package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * Decoder for the `glyphs.pbf` range format.
 *
 * The schema is three messages, from `maplibre-gl-js/src/style/glyphs.proto`:
 *
 * ```proto
 * message glyph {
 *     required uint32 id     = 1;
 *     optional bytes  bitmap = 2;  // A signed distance field, (width + 6) * (height + 6) bytes
 *     required uint32 width  = 3;
 *     required uint32 height = 4;
 *     required sint32 left   = 5;
 *     required sint32 top    = 6;
 *     required uint32 advance = 7;
 * }
 * message fontstack {
 *     required string name  = 1;
 *     required string range = 2;
 *     repeated glyph glyphs = 3;
 * }
 * message glyphs { repeated fontstack stacks = 1; }
 * ```
 *
 * Unlike `spec/vector_tile.kt`, this is not pbandk-generated. Three messages of seven scalar fields
 * do not justify adding a protobuf toolchain to the build, and the wire format they use is a
 * fraction of what a generated decoder handles -- so the schema is quoted above and read directly,
 * the way `MvtFixtures` builds an MVT command stream by hand rather than shipping a binary fixture.
 * `library/tools/fetch-glyphs-proto.sh` re-fetches the upstream `.proto` so the two can be diffed.
 *
 * Unknown fields are skipped rather than rejected, so a server adding one does not break decoding.
 */
internal object GlyphPbf {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /**
     * Decodes one range file.
     *
     * Returns an empty list rather than throwing when the bytes are not a glyph range: a font a
     * server does not have should cost the label its glyphs, not the whole tile.
     */
    fun decode(bytes: ByteArray): List<FontStackGlyphs> = try {
        val reader = Reader(bytes, 0, bytes.size)
        val stacks = mutableListOf<FontStackGlyphs>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            if (tag.field == 1 && tag.wire == WIRE_LENGTH_DELIMITED) {
                stacks += reader.readMessage { decodeFontStack(it) }
            } else {
                reader.skip(tag.wire)
            }
        }
        stacks
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeFontStack(reader: Reader): FontStackGlyphs {
        var name = ""
        var range = ""
        val glyphs = mutableMapOf<Int, Glyph>()
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when {
                tag.field == 1 && tag.wire == WIRE_LENGTH_DELIMITED -> name = reader.readString()
                tag.field == 2 && tag.wire == WIRE_LENGTH_DELIMITED -> range = reader.readString()
                tag.field == 3 && tag.wire == WIRE_LENGTH_DELIMITED -> {
                    val glyph = reader.readMessage { decodeGlyph(it) }
                    glyphs[glyph.id] = glyph
                }

                else -> reader.skip(tag.wire)
            }
        }
        return FontStackGlyphs(fontStack = name, range = range, glyphs = glyphs)
    }

    private fun decodeGlyph(reader: Reader): Glyph {
        var id = 0
        var bitmap: ByteArray? = null
        var width = 0
        var height = 0
        var left = 0
        var top = 0
        var advance = 0
        while (reader.hasMore()) {
            val tag = reader.readTag()
            when {
                tag.field == 1 && tag.wire == WIRE_VARINT -> id = reader.readVarint().toInt()
                tag.field == 2 && tag.wire == WIRE_LENGTH_DELIMITED -> bitmap = reader.readBytes()
                tag.field == 3 && tag.wire == WIRE_VARINT -> width = reader.readVarint().toInt()
                tag.field == 4 && tag.wire == WIRE_VARINT -> height = reader.readVarint().toInt()
                tag.field == 5 && tag.wire == WIRE_VARINT -> left = zigZag(reader.readVarint())
                tag.field == 6 && tag.wire == WIRE_VARINT -> top = zigZag(reader.readVarint())
                tag.field == 7 && tag.wire == WIRE_VARINT -> advance = reader.readVarint().toInt()
                else -> reader.skip(tag.wire)
            }
        }
        return Glyph(
            id = id,
            bitmap = bitmap,
            width = width,
            height = height,
            left = left,
            top = top,
            advance = advance,
        )
    }

    private fun zigZag(value: Long): Int = ((value ushr 1) xor -(value and 1L)).toInt()

    private class Tag(val field: Int, val wire: Int)

    private class Reader(val bytes: ByteArray, var position: Int, val end: Int) {

        fun hasMore(): Boolean = position < end

        fun readTag(): Tag {
            val key = readVarint()
            return Tag(field = (key ushr 3).toInt(), wire = (key and 0x7L).toInt())
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= end) throw IllegalStateException("truncated varint")
                val byte = bytes[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long")
            }
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || position + length > end) throw IllegalStateException("truncated bytes")
            val out = bytes.copyOfRange(position, position + length)
            position += length
            return out
        }

        fun readString(): String = readBytes().decodeToString()

        fun <T> readMessage(block: (Reader) -> T): T {
            val length = readVarint().toInt()
            if (length < 0 || position + length > end) throw IllegalStateException("truncated message")
            val nested = Reader(bytes, position, position + length)
            val result = block(nested)
            position += length
            return result
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> position += 8
                WIRE_LENGTH_DELIMITED -> readBytes()
                WIRE_FIXED32 -> position += 4
                else -> throw IllegalStateException("unknown wire type $wire")
            }
            if (position > end) throw IllegalStateException("truncated field")
        }
    }
}
