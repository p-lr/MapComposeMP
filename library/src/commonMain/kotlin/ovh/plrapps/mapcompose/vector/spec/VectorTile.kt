package ovh.plrapps.mapcompose.vector.spec

/**
 * A decoded Mapbox Vector Tile, as `vector_tile.proto` (spec v2.1) defines it.
 *
 * Hand-written rather than generated, and read by [MvtReader] rather than a protobuf runtime. The
 * generated pbandk model this replaces held `geometry` and `tags` as `List<Int>` -- every coordinate
 * a boxed `Integer`, plus a lazily computed `protoSize` and an unknown-field map per message -- which
 * measured fifteen times the encoded size of a swisstopo tile once decoded, and took ~28 ms to decode
 * one. Here they are `IntArray`s, and nothing is kept that nothing reads.
 *
 * The class and property names are the generated ones, so the code reading a tile did not change.
 */
class Tile(
    val layers: List<Layer> = emptyList(),
) {
    /** The geometry type of a [Feature]; an unknown value is kept as [UNRECOGNIZED], as protobuf enums are. */
    sealed class GeomType(val value: Int, val name: String? = null) {
        override fun equals(other: Any?): Boolean = other is GeomType && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "Tile.GeomType.${name ?: "UNRECOGNIZED"}(value=$value)"

        object UNKNOWN : GeomType(0, "UNKNOWN")
        object POINT : GeomType(1, "POINT")
        object LINESTRING : GeomType(2, "LINESTRING")
        object POLYGON : GeomType(3, "POLYGON")
        class UNRECOGNIZED(value: Int) : GeomType(value)

        companion object {
            val values: List<GeomType> by lazy { listOf(UNKNOWN, POINT, LINESTRING, POLYGON) }

            fun fromValue(value: Int): GeomType = when (value) {
                0 -> UNKNOWN
                1 -> POINT
                2 -> LINESTRING
                3 -> POLYGON
                else -> UNRECOGNIZED(value)
            }
        }
    }

    /** One entry of a layer's value table: exactly one of the fields is set. */
    data class Value(
        val stringValue: String? = null,
        val floatValue: Float? = null,
        val doubleValue: Double? = null,
        val intValue: Long? = null,
        val uintValue: Long? = null,
        val sintValue: Long? = null,
        val boolValue: Boolean? = null,
    )

    /**
     * One feature. [tags] alternates key and value indices into its layer's tables; [geometry] is
     * the MVT command stream, still zig-zag encoded -- see `GeometryDecoders`.
     */
    class Feature(
        val id: Long? = null,
        val tags: IntArray = EMPTY_INTS,
        val type: GeomType? = null,
        val geometry: IntArray = EMPTY_INTS,
    )

    class Layer(
        val version: Int,
        val name: String,
        val features: List<Feature> = emptyList(),
        val keys: List<String> = emptyList(),
        val values: List<Value> = emptyList(),
        val extent: Int? = null,
    ) {
        /**
         * Each key's position in [keys], built once per layer rather than per feature. A key table
         * the encoder repeated a key in has fewer entries here than [keys] has -- see
         * [hasDuplicateKeys].
         */
        internal val keyIndex: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            HashMap<String, Int>(keys.size * 2).also { index ->
                for ((i, key) in keys.withIndex()) if (key !in index) index[key] = i
            }
        }

        internal val hasDuplicateKeys: Boolean get() = keyIndex.size != keys.size

        /**
         * Each entry of [values] as the expression engine reads it -- a string, a boolean or a
         * `Double`, see `normalizeNumbers` -- converted once per layer rather than per feature.
         */
        internal val engineValues: Array<Any?> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Array(values.size) { i -> values[i].engineValue() }
        }
    }

    companion object {
        /** Decodes an MVT protobuf. Throws on bytes that are not one; see [MvtReader]. */
        fun decodeFromByteArray(bytes: ByteArray): Tile = MvtReader.decode(bytes)
    }
}

private val EMPTY_INTS = IntArray(0)

/** The one field that is set, as the expression engine models numbers: always a `Double`. */
internal fun Tile.Value.engineValue(): Any? {
    val raw: Any? = stringValue ?: floatValue ?: doubleValue ?: intValue ?: uintValue ?: sintValue ?: boolValue
    return ovh.plrapps.mapcompose.vector.spec.style.expression.normalizeNumbers(raw)
}
