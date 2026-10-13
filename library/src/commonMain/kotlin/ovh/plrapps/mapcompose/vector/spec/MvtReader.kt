package ovh.plrapps.mapcompose.vector.spec

/**
 * A hand-rolled reader for the Mapbox Vector Tile protobuf, `vector_tile.proto` (spec v2.1):
 *
 * ```
 * message Tile {
 *   enum GeomType { UNKNOWN = 0; POINT = 1; LINESTRING = 2; POLYGON = 3; }
 *   message Value {
 *     optional string string_value = 1; optional float float_value = 2;
 *     optional double double_value = 3; optional int64 int_value = 4;
 *     optional uint64 uint_value = 5; optional sint64 sint_value = 6;
 *     optional bool bool_value = 7;
 *     extensions 8 to max;
 *   }
 *   message Feature {
 *     optional uint64 id = 1 [ default = 0 ];
 *     repeated uint32 tags = 2 [ packed = true ];
 *     optional GeomType type = 3 [ default = UNKNOWN ];
 *     repeated uint32 geometry = 4 [ packed = true ];
 *   }
 *   message Layer {
 *     required uint32 version = 15 [ default = 1 ];
 *     required string name = 1;
 *     repeated Feature features = 2;
 *     repeated string keys = 3;
 *     repeated Value values = 4;
 *     optional uint32 extent = 5 [ default = 4096 ];
 *     extensions 16 to max;
 *   }
 *   repeated Layer layers = 3;
 *   extensions 16 to 8191;
 * }
 * ```
 *
 * Three messages and a dozen scalar fields, the `GlyphPbf` situation again: a generic runtime cost
 * more than the format. A packed field is counted before it is read, so each `IntArray` is allocated
 * once at its exact size. Unknown fields are skipped; a repeated scalar sent unpacked is accepted, as
 * protobuf requires of a reader. The two `required` fields default to what the spec says rather than
 * failing the tile, as `vector-tile-js` does.
 *
 * Bytes that are not a protobuf at all -- a truncated download, an HTML error page -- throw
 * [IllegalArgumentException], which `VectorRasterizer.decodePBFFromByteArray` turns into a missing
 * tile.
 */
internal object MvtReader {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun decode(bytes: ByteArray): Tile {
        val reader = Reader(bytes, 0, bytes.size)
        val layers = ArrayList<Tile.Layer>()
        while (reader.hasMore()) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            if (field == 3 && wire == WIRE_LENGTH_DELIMITED) {
                val end = reader.messageEnd()
                layers += decodeLayer(reader, end)
                reader.position = end
            } else {
                reader.skip(wire)
            }
        }
        return Tile(layers)
    }

    private fun decodeLayer(reader: Reader, end: Int): Tile.Layer {
        var version = 1
        var name = ""
        var extent: Int? = null
        val features = ArrayList<Tile.Feature>()
        val keys = ArrayList<String>()
        val values = ArrayList<Tile.Value>()
        while (reader.position < end) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            when {
                field == 15 && wire == WIRE_VARINT -> version = reader.readVarint().toInt()
                field == 1 && wire == WIRE_LENGTH_DELIMITED -> name = reader.readString()
                field == 2 && wire == WIRE_LENGTH_DELIMITED -> {
                    val featureEnd = reader.messageEnd()
                    features += decodeFeature(reader, featureEnd)
                    reader.position = featureEnd
                }
                field == 3 && wire == WIRE_LENGTH_DELIMITED -> keys += reader.readString()
                field == 4 && wire == WIRE_LENGTH_DELIMITED -> {
                    val valueEnd = reader.messageEnd()
                    values += decodeValue(reader, valueEnd)
                    reader.position = valueEnd
                }
                field == 5 && wire == WIRE_VARINT -> extent = reader.readVarint().toInt()
                else -> reader.skip(wire)
            }
            reader.checkWithin(end)
        }
        return Tile.Layer(
            version = version,
            name = name,
            features = features,
            keys = keys,
            values = values,
            extent = extent,
        )
    }

    private fun decodeFeature(reader: Reader, end: Int): Tile.Feature {
        var id: Long? = null
        var tags: IntArray? = null
        var type: Tile.GeomType? = null
        var geometry: IntArray? = null
        while (reader.position < end) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            when {
                field == 1 && wire == WIRE_VARINT -> id = reader.readVarint()
                field == 2 && wire == WIRE_LENGTH_DELIMITED -> tags = reader.readPacked(tags)
                field == 2 && wire == WIRE_VARINT -> tags = append(tags, reader.readVarint().toInt())
                field == 3 && wire == WIRE_VARINT -> type = Tile.GeomType.fromValue(reader.readVarint().toInt())
                field == 4 && wire == WIRE_LENGTH_DELIMITED -> geometry = reader.readPacked(geometry)
                field == 4 && wire == WIRE_VARINT -> geometry = append(geometry, reader.readVarint().toInt())
                else -> reader.skip(wire)
            }
            reader.checkWithin(end)
        }
        return Tile.Feature(
            id = id,
            tags = tags ?: EMPTY,
            type = type,
            geometry = geometry ?: EMPTY,
        )
    }

    private fun decodeValue(reader: Reader, end: Int): Tile.Value {
        var value = Tile.Value()
        while (reader.position < end) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            value = when {
                field == 1 && wire == WIRE_LENGTH_DELIMITED -> Tile.Value(stringValue = reader.readString())
                field == 2 && wire == WIRE_FIXED32 -> Tile.Value(floatValue = Float.fromBits(reader.readFixed32()))
                field == 3 && wire == WIRE_FIXED64 -> Tile.Value(doubleValue = Double.fromBits(reader.readFixed64()))
                field == 4 && wire == WIRE_VARINT -> Tile.Value(intValue = reader.readVarint())
                field == 5 && wire == WIRE_VARINT -> Tile.Value(uintValue = reader.readVarint())
                field == 6 && wire == WIRE_VARINT -> {
                    val raw = reader.readVarint()
                    Tile.Value(sintValue = (raw ushr 1) xor -(raw and 1L))
                }
                field == 7 && wire == WIRE_VARINT -> Tile.Value(boolValue = reader.readVarint() != 0L)
                else -> {
                    reader.skip(wire)
                    value
                }
            }
            reader.checkWithin(end)
        }
        return value
    }

    /** An unpacked repeated scalar, one element at a time. Rare: every encoder packs these. */
    private fun append(array: IntArray?, value: Int): IntArray =
        if (array == null) intArrayOf(value) else array.copyOf(array.size + 1).also { it[array.size] = value }

    private val EMPTY = IntArray(0)

    private class Reader(val bytes: ByteArray, var position: Int, val end: Int) {

        fun hasMore(): Boolean = position < end

        fun checkWithin(limit: Int) {
            require(position <= limit) { "field overruns its message" }
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(position < end) { "truncated varint" }
                val byte = bytes[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift <= 63) { "varint too long" }
            }
        }

        fun readFixed32(): Int {
            require(position + 4 <= end) { "truncated fixed32" }
            var result = 0
            for (i in 0 until 4) result = result or ((bytes[position + i].toInt() and 0xFF) shl (8 * i))
            position += 4
            return result
        }

        fun readFixed64(): Long {
            require(position + 8 <= end) { "truncated fixed64" }
            var result = 0L
            for (i in 0 until 8) result = result or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
            position += 8
            return result
        }

        /** Reads a length prefix and returns where the delimited field ends, leaving the position at its start. */
        fun messageEnd(): Int {
            val length = readVarint()
            require(length >= 0 && position + length <= end) { "truncated message" }
            return position + length.toInt()
        }

        fun readString(): String {
            val fieldEnd = messageEnd()
            val text = bytes.decodeToString(position, fieldEnd)
            position = fieldEnd
            return text
        }

        /**
         * A packed run of uint32 varints, counted first so the array is allocated once at its exact
         * size. Appended to [existing], since protobuf allows a packed field to arrive in pieces.
         */
        fun readPacked(existing: IntArray?): IntArray {
            val fieldEnd = messageEnd()
            var count = 0
            for (i in position until fieldEnd) if (bytes[i].toInt() and 0x80 == 0) count++
            val offset = existing?.size ?: 0
            val out = if (existing == null) IntArray(count) else existing.copyOf(offset + count)
            var index = offset
            while (position < fieldEnd) {
                var result = 0
                var shift = 0
                while (true) {
                    require(position < fieldEnd) { "truncated packed varint" }
                    val byte = bytes[position++].toInt()
                    if (shift < 32) result = result or ((byte and 0x7F) shl shift)
                    if (byte and 0x80 == 0) break
                    shift += 7
                    require(shift <= 63) { "varint too long" }
                }
                out[index++] = result
            }
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> position += 8
                WIRE_LENGTH_DELIMITED -> position = messageEnd()
                WIRE_FIXED32 -> position += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            require(position <= end) { "truncated field" }
        }
    }
}
