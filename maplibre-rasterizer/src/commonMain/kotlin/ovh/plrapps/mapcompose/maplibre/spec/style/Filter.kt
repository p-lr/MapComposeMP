package ovh.plrapps.mapcompose.maplibre.spec.style

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

fun equalsWithNumberCoercion(a: Any?, b: Any?): Boolean {
    return when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
}

@Serializable(with = FilterSerializer::class)
sealed class Filter {
    companion object {
        const val TYPE_PROPERTY = "\$type"
    }

    abstract fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean

    @Serializable
    @SerialName("all")
    data class All(val filters: List<Filter>) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return filters.all { it.process(featureProperties, zoom) }
        }
    }

    @Serializable
    @SerialName("any")
    data class AnyOf(val filters: List<Filter>) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return filters.any { it.process(featureProperties, zoom) }
        }
    }

    @Serializable
    @SerialName("none")
    data class None(val filters: List<Filter>) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return filters.none { it.process(featureProperties, zoom) }
        }
    }

    @Serializable
    @SerialName("==")
    data class Eq(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return equalsWithNumberCoercion(leftValue, rightValue)
        }
    }

    @Serializable
    @SerialName("!=")
    data class Neq(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return !equalsWithNumberCoercion(leftValue, rightValue)
        }
    }

    @Serializable
    @SerialName(">")
    data class Gt(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue is Number && rightValue is Number -> leftValue.toDouble() > rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue > rightValue
                else -> false
            }
        }
    }

    @Serializable
    @SerialName(">=")
    data class Gte(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue is Number && rightValue is Number -> leftValue.toDouble() >= rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue >= rightValue
                else -> false
            }
        }
    }

    @Serializable
    @SerialName("<")
    data class Lt(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue is Number && rightValue is Number -> leftValue.toDouble() < rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue < rightValue
                else -> false
            }
        }
    }

    @Serializable
    @SerialName("<=")
    data class Lte(val left: FilterOperand, val right: FilterOperand) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue is Number && rightValue is Number -> leftValue.toDouble() <= rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue <= rightValue
                else -> false
            }
        }
    }

    @Serializable
    @SerialName("in")
    data class InList(val key: FilterOperand, val values: List<FilterOperand>) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val keyValue = key.evaluate(featureProperties, zoom)
            return values.any { it.evaluate(featureProperties, zoom) == keyValue }
        }
    }

    @Serializable
    @SerialName("!in")
    data class NotInList(val key: FilterOperand, val values: List<FilterOperand>) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            val keyValue = key.evaluate(featureProperties, zoom)
            return values.none { it.evaluate(featureProperties, zoom) == keyValue }
        }
    }

    @Serializable
    @SerialName("has")
    data class Has(val key: String) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return featureProperties.containsKey(key)
        }
    }

    @Serializable
    @SerialName("!has")
    data class NotHas(val key: String) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return !featureProperties.containsKey(key)
        }
    }

    @Serializable
    data class ZoomGreaterThan(
        @Contextual val value: Number
    ) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return zoom > value.toDouble()
        }
    }

    @Serializable
    data class ZoomGreaterThanOrEqual(
        @Contextual val value: Number
    ) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return zoom >= value.toDouble()
        }
    }

    @Serializable
    data class ZoomLessThan(
        @Contextual val value: Number
    ) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return zoom < value.toDouble()
        }
    }

    @Serializable
    data class ZoomLessThanOrEqual(
        @Contextual val value: Number
    ) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return zoom <= value.toDouble()
        }
    }

    @Serializable
    data class Type(
        val value: String
    ) : Filter() {
        override fun process(featureProperties: Map<String, Any?>, zoom: Double): Boolean {
            return featureProperties["type"] as? String == value
        }
    }
}

@Serializable
sealed class FilterOperand {
    abstract fun evaluate(featureProperties: Map<String, Any?>, zoom: Double): Any?

    @Serializable
    @SerialName("get")
    data class Get(val property: String) : FilterOperand() {
        override fun evaluate(featureProperties: Map<String, Any?>, zoom: Double): Any? {
            return featureProperties[property]
        }
    }

    @Serializable
    @SerialName("to-boolean")
    data class ToBoolean(val operand: FilterOperand) : FilterOperand() {
        override fun evaluate(featureProperties: Map<String, Any?>, zoom: Double): Any? {
            val value = operand.evaluate(featureProperties, zoom)
            return when (value) {
                null -> false
                is Boolean -> value
                is String -> value.isNotEmpty()
                is Number -> value.toDouble() != 0.0
                is Collection<*> -> value.isNotEmpty()
                else -> true
            }
        }
    }

    @Serializable
    data class Literal(@Contextual val value: Any) : FilterOperand() {
        override fun evaluate(featureProperties: Map<String, Any?>, zoom: Double): Any? {
            return value
        }
    }
}

@Serializer(forClass = Any::class)
object AnySerializer : KSerializer<Any> {
    override val descriptor = PrimitiveSerialDescriptor("Any", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Any) {
        when (value) {
            is String -> encoder.encodeString(value)
            is Number -> encoder.encodeDouble(value.toDouble())
            is Boolean -> encoder.encodeBoolean(value)
            else -> throw SerializationException("Unsupported type: ${value::class}")
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        return when (val value = decoder.decodeString()) {
            "true" -> true
            "false" -> false
            else -> {
                value.toDoubleOrNull() ?: value
            }
        }
    }
}

object FilterSerializer : KSerializer<Filter> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Filter")

    override fun deserialize(decoder: Decoder): Filter {
        val input = decoder as? JsonDecoder ?: error("FilterSerializer only works with JSON")
        val json = input.decodeJsonElement()
        val arr = json.jsonArray
        if (arr.isEmpty()) error("Filter array is empty")
        val op = arr[0].jsonPrimitive.content
        fun getOperand(el: JsonElement, isKey: Boolean = false): FilterOperand =
            when {
                el is JsonArray && el.size == 2 && el[0].jsonPrimitive.content == "get" ->
                    FilterOperand.Get(el[1].jsonPrimitive.content)
                el is JsonArray && el.size == 2 && el[0].jsonPrimitive.content == "to-boolean" ->
                    FilterOperand.ToBoolean(getOperand(el[1]))
                el is JsonPrimitive && el.isString && isKey ->
                    FilterOperand.Get(el.content)
                el is JsonPrimitive && el.isString && !isKey ->
                    FilterOperand.Literal(el.content)
                else ->
                    FilterOperand.Literal(input.json.decodeFromJsonElement(AnySerializer, el))
            }
        return when (op) {
            "all" -> Filter.All(arr.drop(1).map { input.json.decodeFromJsonElement(FilterSerializer, it) })
            "any" -> Filter.AnyOf(arr.drop(1).map { input.json.decodeFromJsonElement(FilterSerializer, it) })
            "none" -> Filter.None(arr.drop(1).map { input.json.decodeFromJsonElement(FilterSerializer, it) })
            "==" -> Filter.Eq(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            "!=" -> Filter.Neq(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            ">" -> Filter.Gt(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            ">=" -> Filter.Gte(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            "<" -> Filter.Lt(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            "<=" -> Filter.Lte(getOperand(arr[1], isKey = true), getOperand(arr[2]))
            "in" -> Filter.InList(getOperand(arr[1], isKey = true), arr.drop(2).map { getOperand(it, isKey = false) })
            "!in" -> Filter.NotInList(getOperand(arr[1], isKey = true), arr.drop(2).map { getOperand(it, isKey = false) })
            "has" -> Filter.Has(arr[1].jsonPrimitive.content)
            "!has" -> Filter.NotHas(arr[1].jsonPrimitive.content)
            "to-boolean" -> Filter.Eq(getOperand(arr[1]), FilterOperand.Literal(true))
            else -> error("Unknown filter operator: $op")
        }
    }

    override fun serialize(encoder: Encoder, value: Filter) {
        val output = encoder as? JsonEncoder ?: error("FilterSerializer only works with JSON")
        val arr = when (value) {
            is Filter.All -> buildJsonArray {
                add(JsonPrimitive("all"))
                value.filters.forEach { add(output.json.encodeToJsonElement(FilterSerializer, it)) }
            }
            is Filter.AnyOf -> buildJsonArray {
                add(JsonPrimitive("any"))
                value.filters.forEach { add(output.json.encodeToJsonElement(FilterSerializer, it)) }
            }
            is Filter.None -> buildJsonArray {
                add(JsonPrimitive("none"))
                value.filters.forEach { add(output.json.encodeToJsonElement(FilterSerializer, it)) }
            }
            is Filter.Eq -> buildJsonArray {
                add(JsonPrimitive("=="))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.Neq -> buildJsonArray {
                add(JsonPrimitive("!="))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.Gt -> buildJsonArray {
                add(JsonPrimitive(">"))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.Gte -> buildJsonArray {
                add(JsonPrimitive(">="))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.Lt -> buildJsonArray {
                add(JsonPrimitive("<"))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.Lte -> buildJsonArray {
                add(JsonPrimitive("<="))
                add(operandToJson(value.left))
                add(operandToJson(value.right))
            }
            is Filter.InList -> buildJsonArray {
                add(JsonPrimitive("in"))
                add(operandToJson(value.key))
                value.values.forEach { add(operandToJson(it)) }
            }
            is Filter.NotInList -> buildJsonArray {
                add(JsonPrimitive("!in"))
                add(operandToJson(value.key))
                value.values.forEach { add(operandToJson(it)) }
            }
            is Filter.Has -> buildJsonArray {
                add(JsonPrimitive("has"))
                add(JsonPrimitive(value.key))
            }
            is Filter.NotHas -> buildJsonArray {
                add(JsonPrimitive("!has"))
                add(JsonPrimitive(value.key))
            }
            else -> error("Serialization not implemented for this filter type")
        }
        output.encodeJsonElement(arr)
    }

    private fun operandToJson(op: FilterOperand): JsonElement = when (op) {
        is FilterOperand.Get -> buildJsonArray {
            add(JsonPrimitive("get"))
            add(JsonPrimitive(op.property))
        }
        is FilterOperand.ToBoolean -> buildJsonArray {
            add(JsonPrimitive("to-boolean"))
            add(operandToJson(op.operand))
        }
        is FilterOperand.Literal -> Json.encodeToJsonElement(AnySerializer, op.value)
    }
}