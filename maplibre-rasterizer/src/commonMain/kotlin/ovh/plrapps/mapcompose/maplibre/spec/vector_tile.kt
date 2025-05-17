@file:OptIn(pbandk.PublicForGeneratedCode::class)

package ovh.plrapps.mapcompose.maplibre.spec

@pbandk.Export
public data class Tile(
    val layers: List<Layer> = emptyList(),
    override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap(),
    @property:pbandk.PbandkInternal
    override val extensionFields: pbandk.ExtensionFieldSet = pbandk.ExtensionFieldSet()
) : pbandk.ExtendableMessage {
    override operator fun plus(other: pbandk.Message?): Tile = protoMergeImpl(other)
    override val descriptor: pbandk.MessageDescriptor<Tile> get() = Companion.descriptor
    override val protoSize: Int by lazy { super.protoSize }
    public companion object : pbandk.Message.Companion<Tile> {
        public val defaultInstance: Tile by lazy { Tile() }
        override fun decodeWith(u: pbandk.MessageDecoder): Tile = Tile.decodeWithImpl(u)

        override val descriptor: pbandk.MessageDescriptor<Tile> = pbandk.MessageDescriptor(
            fullName = "vector_tile.Tile",
            messageClass = Tile::class,
            messageCompanion = this,
            fields = buildList(1) {
                add(
                    pbandk.FieldDescriptor(
                        messageDescriptor = this@Companion::descriptor,
                        name = "layers",
                        number = 3,
                        type = pbandk.FieldDescriptor.Type.Repeated<Layer>(valueType = pbandk.FieldDescriptor.Type.Message(messageCompanion = Layer.Companion)),
                        jsonName = "layers",
                        value = Tile::layers
                    )
                )
            }
        )
    }

    public sealed class GeomType(override val value: Int, override val name: String? = null) : pbandk.Message.Enum {
        override fun equals(other: Any?): Boolean = other is GeomType && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "Tile.GeomType.${name ?: "UNRECOGNIZED"}(value=$value)"

        public object UNKNOWN : GeomType(0, "UNKNOWN")
        public object POINT : GeomType(1, "POINT")
        public object LINESTRING : GeomType(2, "LINESTRING")
        public object POLYGON : GeomType(3, "POLYGON")
        public class UNRECOGNIZED(value: Int) : GeomType(value)

        public companion object : pbandk.Message.Enum.Companion<GeomType> {
            public val values: List<GeomType> by lazy { listOf(UNKNOWN, POINT, LINESTRING, POLYGON) }
            override fun fromValue(value: Int): GeomType = values.firstOrNull { it.value == value } ?: UNRECOGNIZED(value)
            override fun fromName(name: String): GeomType = values.firstOrNull { it.name == name } ?: throw IllegalArgumentException("No GeomType with name: $name")
        }
    }

    public data class Value(
        val stringValue: String? = null,
        val floatValue: Float? = null,
        val doubleValue: Double? = null,
        val intValue: Long? = null,
        val uintValue: Long? = null,
        val sintValue: Long? = null,
        val boolValue: Boolean? = null,
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap(),
        @property:pbandk.PbandkInternal
        override val extensionFields: pbandk.ExtensionFieldSet = pbandk.ExtensionFieldSet()
    ) : pbandk.ExtendableMessage {
        override operator fun plus(other: pbandk.Message?): Value = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<Value> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<Value> {
            public val defaultInstance: Value by lazy { Value() }
            override fun decodeWith(u: pbandk.MessageDecoder): Value = Value.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<Value> = pbandk.MessageDescriptor(
                fullName = "vector_tile.Tile.Value",
                messageClass = Value::class,
                messageCompanion = this,
                fields = buildList(7) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "string_value",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(hasPresence = true),
                            jsonName = "stringValue",
                            value = Value::stringValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "float_value",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Primitive.Float(hasPresence = true),
                            jsonName = "floatValue",
                            value = Value::floatValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "double_value",
                            number = 3,
                            type = pbandk.FieldDescriptor.Type.Primitive.Double(hasPresence = true),
                            jsonName = "doubleValue",
                            value = Value::doubleValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "int_value",
                            number = 4,
                            type = pbandk.FieldDescriptor.Type.Primitive.Int64(hasPresence = true),
                            jsonName = "intValue",
                            value = Value::intValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "uint_value",
                            number = 5,
                            type = pbandk.FieldDescriptor.Type.Primitive.UInt64(hasPresence = true),
                            jsonName = "uintValue",
                            value = Value::uintValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "sint_value",
                            number = 6,
                            type = pbandk.FieldDescriptor.Type.Primitive.SInt64(hasPresence = true),
                            jsonName = "sintValue",
                            value = Value::sintValue
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "bool_value",
                            number = 7,
                            type = pbandk.FieldDescriptor.Type.Primitive.Bool(hasPresence = true),
                            jsonName = "boolValue",
                            value = Value::boolValue
                        )
                    )
                }
            )
        }
    }

    public data class Feature(
        val id: Long? = null,
        val tags: List<Int> = emptyList(),
        val type: GeomType? = null,
        val geometry: List<Int> = emptyList(),
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap()
    ) : pbandk.Message {
        override operator fun plus(other: pbandk.Message?): Feature = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<Feature> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<Feature> {
            public val defaultInstance: Feature by lazy { Feature() }
            override fun decodeWith(u: pbandk.MessageDecoder): Feature = Feature.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<Feature> = pbandk.MessageDescriptor(
                fullName = "vector_tile.Tile.Feature",
                messageClass = Feature::class,
                messageCompanion = this,
                fields = buildList(4) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "id",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.UInt64(hasPresence = true),
                            jsonName = "id",
                            value = Feature::id
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "tags",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Repeated<Int>(valueType = pbandk.FieldDescriptor.Type.Primitive.UInt32(), packed = true),
                            jsonName = "tags",
                            value = Feature::tags
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "type",
                            number = 3,
                            type = pbandk.FieldDescriptor.Type.Enum(enumCompanion = GeomType.Companion, hasPresence = true),
                            jsonName = "type",
                            value = Feature::type
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "geometry",
                            number = 4,
                            type = pbandk.FieldDescriptor.Type.Repeated<Int>(valueType = pbandk.FieldDescriptor.Type.Primitive.UInt32(), packed = true),
                            jsonName = "geometry",
                            value = Feature::geometry
                        )
                    )
                }
            )
        }
    }

    public data class Layer(
        val version: Int,
        val name: String,
        val features: List<Feature> = emptyList(),
        val keys: List<String> = emptyList(),
        val values: List<Value> = emptyList(),
        val extent: Int? = null,
        override val unknownFields: Map<Int, pbandk.UnknownField> = emptyMap(),
        @property:pbandk.PbandkInternal
        override val extensionFields: pbandk.ExtensionFieldSet = pbandk.ExtensionFieldSet()
    ) : pbandk.ExtendableMessage {
        override operator fun plus(other: pbandk.Message?): Layer = protoMergeImpl(other)
        override val descriptor: pbandk.MessageDescriptor<Layer> get() = Companion.descriptor
        override val protoSize: Int by lazy { super.protoSize }
        public companion object : pbandk.Message.Companion<Layer> {
            override fun decodeWith(u: pbandk.MessageDecoder): Layer = Layer.decodeWithImpl(u)

            override val descriptor: pbandk.MessageDescriptor<Layer> = pbandk.MessageDescriptor(
                fullName = "vector_tile.Tile.Layer",
                messageClass = Layer::class,
                messageCompanion = this,
                fields = buildList(6) {
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "name",
                            number = 1,
                            type = pbandk.FieldDescriptor.Type.Primitive.String(hasPresence = true),
                            jsonName = "name",
                            value = Layer::name
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "features",
                            number = 2,
                            type = pbandk.FieldDescriptor.Type.Repeated<Feature>(valueType = pbandk.FieldDescriptor.Type.Message(messageCompanion = Feature.Companion)),
                            jsonName = "features",
                            value = Layer::features
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "keys",
                            number = 3,
                            type = pbandk.FieldDescriptor.Type.Repeated<String>(valueType = pbandk.FieldDescriptor.Type.Primitive.String()),
                            jsonName = "keys",
                            value = Layer::keys
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "values",
                            number = 4,
                            type = pbandk.FieldDescriptor.Type.Repeated<Value>(valueType = pbandk.FieldDescriptor.Type.Message(messageCompanion = Value.Companion)),
                            jsonName = "values",
                            value = Layer::values
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "extent",
                            number = 5,
                            type = pbandk.FieldDescriptor.Type.Primitive.UInt32(hasPresence = true),
                            jsonName = "extent",
                            value = Layer::extent
                        )
                    )
                    add(
                        pbandk.FieldDescriptor(
                            messageDescriptor = this@Companion::descriptor,
                            name = "version",
                            number = 15,
                            type = pbandk.FieldDescriptor.Type.Primitive.UInt32(hasPresence = true),
                            jsonName = "version",
                            value = Layer::version
                        )
                    )
                }
            )
        }
    }
}

@pbandk.Export
@pbandk.JsName("orDefaultForTile")
public fun Tile?.orDefault(): Tile = this ?: Tile.defaultInstance

private fun Tile.protoMergeImpl(plus: pbandk.Message?): Tile = (plus as? Tile)?.let {
    it.copy(
        layers = layers + plus.layers,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun Tile.Companion.decodeWithImpl(u: pbandk.MessageDecoder): Tile {
    var layers: pbandk.ListWithSize.Builder<Tile.Layer>? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            3 -> layers = (layers ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<Tile.Layer> }
        }
    }

    return Tile(pbandk.ListWithSize.Builder.fixed(layers), unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForTileValue")
public fun Tile.Value?.orDefault(): Tile.Value = this ?: Tile.Value.defaultInstance

private fun Tile.Value.protoMergeImpl(plus: pbandk.Message?): Tile.Value = (plus as? Tile.Value)?.let {
    it.copy(
        stringValue = plus.stringValue ?: stringValue,
        floatValue = plus.floatValue ?: floatValue,
        doubleValue = plus.doubleValue ?: doubleValue,
        intValue = plus.intValue ?: intValue,
        uintValue = plus.uintValue ?: uintValue,
        sintValue = plus.sintValue ?: sintValue,
        boolValue = plus.boolValue ?: boolValue,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun Tile.Value.Companion.decodeWithImpl(u: pbandk.MessageDecoder): Tile.Value {
    var stringValue: String? = null
    var floatValue: Float? = null
    var doubleValue: Double? = null
    var intValue: Long? = null
    var uintValue: Long? = null
    var sintValue: Long? = null
    var boolValue: Boolean? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> stringValue = _fieldValue as String
            2 -> floatValue = _fieldValue as Float
            3 -> doubleValue = _fieldValue as Double
            4 -> intValue = _fieldValue as Long
            5 -> uintValue = _fieldValue as Long
            6 -> sintValue = _fieldValue as Long
            7 -> boolValue = _fieldValue as Boolean
        }
    }

    return Tile.Value(stringValue, floatValue, doubleValue, intValue,
        uintValue, sintValue, boolValue, unknownFields)
}

@pbandk.Export
@pbandk.JsName("orDefaultForTileFeature")
public fun Tile.Feature?.orDefault(): Tile.Feature = this ?: Tile.Feature.defaultInstance

private fun Tile.Feature.protoMergeImpl(plus: pbandk.Message?): Tile.Feature = (plus as? Tile.Feature)?.let {
    it.copy(
        id = plus.id ?: id,
        tags = tags + plus.tags,
        type = plus.type ?: type,
        geometry = geometry + plus.geometry,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun Tile.Feature.Companion.decodeWithImpl(u: pbandk.MessageDecoder): Tile.Feature {
    var id: Long? = null
    var tags: pbandk.ListWithSize.Builder<Int>? = null
    var type: Tile.GeomType? = null
    var geometry: pbandk.ListWithSize.Builder<Int>? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> id = _fieldValue as Long
            2 -> tags = (tags ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<Int> }
            3 -> type = _fieldValue as Tile.GeomType
            4 -> geometry = (geometry ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<Int> }
        }
    }

    return Tile.Feature(id, pbandk.ListWithSize.Builder.fixed(tags), type, pbandk.ListWithSize.Builder.fixed(geometry), unknownFields)
}

private fun Tile.Layer.protoMergeImpl(plus: pbandk.Message?): Tile.Layer = (plus as? Tile.Layer)?.let {
    it.copy(
        features = features + plus.features,
        keys = keys + plus.keys,
        values = values + plus.values,
        extent = plus.extent ?: extent,
        unknownFields = unknownFields + plus.unknownFields
    )
} ?: this

@Suppress("UNCHECKED_CAST")
private fun Tile.Layer.Companion.decodeWithImpl(u: pbandk.MessageDecoder): Tile.Layer {
    var version: Int? = null
    var name: String? = null
    var features: pbandk.ListWithSize.Builder<Tile.Feature>? = null
    var keys: pbandk.ListWithSize.Builder<String>? = null
    var values: pbandk.ListWithSize.Builder<Tile.Value>? = null
    var extent: Int? = null

    val unknownFields = u.readMessage(this) { _fieldNumber, _fieldValue ->
        when (_fieldNumber) {
            1 -> name = _fieldValue as String
            2 -> features = (features ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<Tile.Feature> }
            3 -> keys = (keys ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<String> }
            4 -> values = (values ?: pbandk.ListWithSize.Builder()).apply { this += _fieldValue as Sequence<Tile.Value> }
            5 -> extent = _fieldValue as Int
            15 -> version = _fieldValue as Int
        }
    }

    if (version == null) {
        throw pbandk.InvalidProtocolBufferException.missingRequiredField("version")
    }
    if (name == null) {
        throw pbandk.InvalidProtocolBufferException.missingRequiredField("name")
    }
    return Tile.Layer(version!!, name!!, pbandk.ListWithSize.Builder.fixed(features), pbandk.ListWithSize.Builder.fixed(keys),
        pbandk.ListWithSize.Builder.fixed(values), extent, unknownFields)
}
