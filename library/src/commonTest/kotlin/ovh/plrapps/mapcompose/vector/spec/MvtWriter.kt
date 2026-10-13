package ovh.plrapps.mapcompose.vector.spec

/**
 * Encodes a [Tile] as an MVT protobuf, for tests that need bytes -- a tile served to a rasterizer,
 * or [MvtReader] fed something it must read back exactly.
 *
 * Packed fields are written packed, as every real encoder does; [MvtReaderTest] covers the unpacked
 * form separately.
 */
internal fun Tile.encodeToByteArray(): ByteArray {
    val out = ProtoOut()
    for (layer in layers) out.message(3, layer.encode())
    return out.bytes()
}

private fun Tile.Layer.encode(): ByteArray {
    val out = ProtoOut()
    out.varintField(15, version.toLong())
    out.stringField(1, name)
    for (feature in features) out.message(2, feature.encode())
    for (key in keys) out.stringField(3, key)
    for (value in values) out.message(4, value.encode())
    extent?.let { out.varintField(5, it.toLong()) }
    return out.bytes()
}

private fun Tile.Feature.encode(): ByteArray {
    val out = ProtoOut()
    id?.let { out.varintField(1, it) }
    if (tags.isNotEmpty()) out.packed(2, tags)
    type?.let { out.varintField(3, it.value.toLong()) }
    if (geometry.isNotEmpty()) out.packed(4, geometry)
    return out.bytes()
}

private fun Tile.Value.encode(): ByteArray {
    val out = ProtoOut()
    stringValue?.let { out.stringField(1, it) }
    floatValue?.let { out.tag(2, 5); out.fixed32(it.toRawBits()) }
    doubleValue?.let { out.tag(3, 1); out.fixed64(it.toRawBits()) }
    intValue?.let { out.varintField(4, it) }
    uintValue?.let { out.varintField(5, it) }
    sintValue?.let { out.varintField(6, (it shl 1) xor (it shr 63)) }
    boolValue?.let { out.varintField(7, if (it) 1 else 0) }
    return out.bytes()
}

internal class ProtoOut {
    private val buffer = ArrayList<Byte>()

    fun bytes(): ByteArray = buffer.toByteArray()

    fun raw(bytes: ByteArray) {
        for (b in bytes) buffer += b
    }

    fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                buffer += v.toByte()
                return
            }
            buffer += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun varintField(field: Int, value: Long) {
        tag(field, 0)
        varint(value)
    }

    fun bytesField(field: Int, value: ByteArray) {
        tag(field, 2)
        varint(value.size.toLong())
        raw(value)
    }

    fun stringField(field: Int, value: String) = bytesField(field, value.encodeToByteArray())

    fun message(field: Int, value: ByteArray) = bytesField(field, value)

    fun packed(field: Int, values: IntArray) {
        val inner = ProtoOut()
        for (v in values) inner.varint(v.toLong() and 0xFFFFFFFFL)
        bytesField(field, inner.bytes())
    }

    fun fixed32(value: Int) {
        for (i in 0 until 4) buffer += (value ushr (8 * i)).toByte()
    }

    fun fixed64(value: Long) {
        for (i in 0 until 8) buffer += (value ushr (8 * i)).toByte()
    }
}
