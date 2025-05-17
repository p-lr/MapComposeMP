package ovh.plrapps.mapcompose.maplibre.spec.style.serializers

import ovh.plrapps.mapcompose.maplibre.spec.style.props.Expr
import ovh.plrapps.mapcompose.maplibre.spec.style.props.InterpolationType
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.isExpr
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.normalizeLegacyExpression
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.normalizePotentialLiteralArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object ExprSerializerFactory {
    fun <T : Any> create(valueSerializer: KSerializer<T>): KSerializer<Expr<T>> {
        return ExprSerializer(valueSerializer)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = Expr::class)
class ExprSerializer<T : Any>(
    private val valueSerializer: KSerializer<T>
) : KSerializer<Expr<T>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Expr")

    private fun serializeGet(value: Expr.Get<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                )
            )
        )
    }

    private fun serializeMatch(value: Expr.Match<T>, encoder: JsonEncoder) {
        val elements = mutableListOf<JsonElement>(JsonPrimitive("match"))
        // input
        elements.add(serializeExpr(value.input, encoder))

        for ((values, expr) in value.branches) {
            if (values.size == 1) {
                elements.add(JsonPrimitive(values[0]))
            } else {
                elements.add(JsonArray(values.map { JsonPrimitive(it) }))
            }
            elements.add(serializeExpr(expr, encoder))
        }
        // else
        elements.add(serializeExpr(value.elseExpr, encoder))
        encoder.encodeJsonElement(JsonArray(elements))
    }

    private fun serializeZoomStops(value: Expr.ZoomStops<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("zoom"),
                    JsonArray(value.stops.map { (zoom, value) ->
                        JsonArray(
                            listOf(
                                JsonPrimitive(zoom),
                                encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                            )
                        )
                    })
                )
            )
        )
    }

    private fun serializeConstant(value: Expr.Constant<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(encoder.json.encodeToJsonElement(valueSerializer, value.value))
    }

    private fun serializeRaw(value: Expr.Raw<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(value.json)
    }

    private fun serializeStops(value: Expr.Stops<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonObject(
                mapOf(
                    "stops" to JsonArray(value.stops.map { (stop, value) ->
                        JsonArray(
                            listOf(
                                JsonPrimitive(stop),
                                encoder.json.encodeToJsonElement(valueSerializer, value)
                            )
                        )
                    })
                )
            )
        )
    }

    private fun serializeHas(value: Expr.Has<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("has"),
                    JsonPrimitive(value.property)
                )
            )
        )
    }

    private fun serializeIn(value: Expr.In<T>, encoder: JsonEncoder) {
        val evaluatedValue = value.value.evaluate(null, null)
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("in"),
                    JsonPrimitive(evaluatedValue?.toString() ?: ""),
                    JsonArray(value.array.map { JsonPrimitive(it) })
                )
            )
        )
    }

    private fun serializeCase(value: Expr.Case<T>, encoder: JsonEncoder) {
        val elements = mutableListOf<JsonElement>(JsonPrimitive("case"))
        value.conditions.forEach { (condition, result) ->
            elements.add(serializeExpr(condition, encoder))
            elements.add(serializeExpr(result, encoder))
        }
        elements.add(serializeExpr(value.default, encoder))
        encoder.encodeJsonElement(JsonArray(elements))
    }

    private fun serializeCoalesce(value: Expr.Coalesce<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("coalesce")
                ) + value.values.map { expr ->
                    val evaluatedValue = expr.evaluate(null, null)
                    when (evaluatedValue) {
                        null -> JsonNull
                        is T -> encoder.json.encodeToJsonElement(valueSerializer, evaluatedValue)
                        else -> JsonPrimitive(evaluatedValue.toString())
                    }
                })
        )
    }

    private fun serializeInterpolate(value: Expr.Interpolate<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("interpolate"),
                    JsonArray(
                        listOf(
                            JsonPrimitive(
                                when (value.interpolation) {
                                    is InterpolationType.Linear -> "linear"
                                    is InterpolationType.Exponential -> "exponential"
                                    is InterpolationType.Cubic -> "cubic"
                                    is InterpolationType.Step -> "step"
                                }
                            )
                        )
                    ),
                    JsonPrimitive(value.input.toString())
                ) + value.stops.flatMap { (stop, expr) ->
                    listOf(
                        JsonPrimitive(stop),
                        serializeExpr(expr, encoder)
                    )
                })
        )
    }

    private fun serializeAt(value: Expr.At<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("at"),
                    JsonPrimitive(value.index),
                    JsonArray((value.array.evaluate(null, null) ?: emptyList()).map { item ->
                        when (item) {
                            is T -> encoder.json.encodeToJsonElement(valueSerializer, item)
                            else -> JsonPrimitive(item.toString())
                        }
                    })
                )
            )
        )
    }

    private fun serializeLength(value: Expr.Length<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("length"),
                    JsonArray((value.array.evaluate(null, null) ?: emptyList()).map { item ->
                        when (item) {
                            is T -> encoder.json.encodeToJsonElement(valueSerializer, item)
                            else -> JsonPrimitive(item.toString())
                        }
                    })
                )
            )
        )
    }

    private fun serializeSlice(value: Expr.Slice<T>, encoder: JsonEncoder) {
        val elements = mutableListOf<JsonElement>(
            JsonPrimitive("slice"),
            JsonArray((value.array.evaluate(null, null) ?: emptyList()).map { item ->
                when (item) {
                    is T -> encoder.json.encodeToJsonElement(valueSerializer, item)
                    else -> JsonPrimitive(item.toString())
                }
            }),
            JsonPrimitive(value.start)
        )
        if (value.end != null) {
            elements.add(JsonPrimitive(value.end))
        }
        encoder.encodeJsonElement(JsonArray(elements))
    }

    private fun serializeNotEquals(value: Expr.NotEquals<*, *>, encoder: JsonEncoder) {
        val leftElement = when (val left = value.left) {
            is Expr.Get<*> -> JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                )
            )

            else -> JsonPrimitive(left.evaluate(null, null)?.toString() ?: "")
        }

        val rightElement = when (val right = value.right) {
            is Expr.Get<*> -> JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                )
            )

            else -> JsonPrimitive(right.evaluate(null, null)?.toString() ?: "")
        }

        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("!="),
                    leftElement,
                    rightElement
                )
            )
        )
    }

    private fun serializeEquals(value: Expr.Equals<*, *>, encoder: JsonEncoder) {
        val leftElement = when (val left = value.left) {
            is Expr.Get<*> -> JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                )
            )

            else -> JsonPrimitive(left.evaluate(null, null)?.toString() ?: "")
        }

        val rightElement = when (val right = value.right) {
            is Expr.Get<*> -> JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    encoder.json.encodeToJsonElement(valueSerializer, (value as T))
                )
            )

            else -> JsonPrimitive(right.evaluate(null, null)?.toString() ?: "")
        }

        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("=="),
                    leftElement,
                    rightElement
                )
            )
        )
    }

    private fun serializeExpr(expr: Expr<*>, jsonEncoder: JsonEncoder): JsonElement {
        return when (expr) {
            is Expr.Get -> JsonArray(
                listOf(
                    JsonPrimitive("get"),
                    jsonEncoder.json.encodeToJsonElement(valueSerializer, (expr as T))
                )
            )

            is Expr.Constant -> when (val value = expr.value) {
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonNull
            }

            is Expr.Concat<*> -> JsonArray(
                listOf(
                    JsonPrimitive("concat"),
                    serializeExpr(expr.left, jsonEncoder),
                    serializeExpr(expr.right, jsonEncoder)
                )
            )

            else -> JsonNull
        }
    }

    private fun serializeLessThan(expr: Expr.LessThan<*>, jsonEncoder: JsonEncoder) {
        jsonEncoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("<"),
                    serializeExpr(expr.left, jsonEncoder),
                    serializeExpr(expr.right, jsonEncoder)
                )
            )
        )
    }

    private fun serializeLessThanOrEqual(expr: Expr.LessThanOrEqual<*>, jsonEncoder: JsonEncoder) {
        jsonEncoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("<="),
                    serializeExpr(expr.left, jsonEncoder),
                    serializeExpr(expr.right, jsonEncoder)
                )
            )
        )
    }

    private fun serializeGreaterThan(expr: Expr.GreaterThan<*>, jsonEncoder: JsonEncoder) {
        jsonEncoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive(">"),
                    serializeExpr(expr.left, jsonEncoder),
                    serializeExpr(expr.right, jsonEncoder)
                )
            )
        )
    }

    private fun serializeGreaterThanOrEqual(expr: Expr.GreaterThanOrEqual<*>, jsonEncoder: JsonEncoder) {
        jsonEncoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive(">="),
                    serializeExpr(expr.left, jsonEncoder),
                    serializeExpr(expr.right, jsonEncoder)
                )
            )
        )
    }

    private fun serializeAll(value: Expr.All, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("all")
                ) + value.conditions.map { condition ->
                    serializeExpr(condition, encoder)
                })
        )
    }

    private fun serializeAnyOf(value: Expr.AnyOf, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("any")
                ) + value.conditions.map { condition ->
                    serializeExpr(condition, encoder)
                })
        )
    }

    private fun serializeNot(value: Expr.Not, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("!"),
                    serializeExpr(value.condition, encoder)
                )
            )
        )
    }

    private fun serializeModulo(value: Expr.Modulo, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("%"),
                    serializeExpr(value.left, encoder),
                    serializeExpr(value.right, encoder)
                )
            )
        )
    }

    private fun serializePower(value: Expr.Power, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("^"),
                    serializeExpr(value.left, encoder),
                    serializeExpr(value.right, encoder)
                )
            )
        )
    }

    private fun serializeAbs(value: Expr.Abs, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("abs"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeAcos(value: Expr.Acos, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("acos"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeAsin(value: Expr.Asin, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("asin"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeAtan(value: Expr.Atan, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("atan"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeCeil(value: Expr.Ceil, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("ceil"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeCos(value: Expr.Cos, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("cos"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeFloor(value: Expr.Floor, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("floor"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeLn(value: Expr.Ln, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("ln"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeLog10(value: Expr.Log10, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("log10"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeMax(value: Expr.Max, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("max"),
                    serializeExpr(value.left, encoder),
                    serializeExpr(value.right, encoder)
                )
            )
        )
    }

    private fun serializeMin(value: Expr.Min, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("min"),
                    serializeExpr(value.left, encoder),
                    serializeExpr(value.right, encoder)
                )
            )
        )
    }

    private fun serializeRound(value: Expr.Round, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("round"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeSin(value: Expr.Sin, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("sin"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeSqrt(value: Expr.Sqrt, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("sqrt"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeTan(value: Expr.Tan, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("tan"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeConcat(value: Expr.Concat<T>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("concat"),
                    serializeExpr(value.left, encoder),
                    serializeExpr(value.right, encoder)
                )
            )
        )
    }

    private fun serializeIndexOf(value: Expr.IndexOf<*>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("index-of"),
                    serializeExpr(value.array, encoder),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeDowncase(value: Expr.Downcase, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("downcase"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeUpcase(value: Expr.Upcase, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("upcase"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeToBoolean(value: Expr.ToBoolean, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("to-boolean"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    private fun serializeToStringExpr(value: Expr.ToString<*>, encoder: JsonEncoder) {
        encoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive("to-string"),
                    serializeExpr(value.value, encoder)
                )
            )
        )
    }

    override fun serialize(encoder: Encoder, value: Expr<T>) {
        val jsonEncoder =
            encoder as? JsonEncoder ?: throw SerializationException("This serializer can only be used with Json")

        // FIXME not working
        when (value) {
            is Expr.Get -> serializeGet(value, jsonEncoder)
            is Expr.Match -> serializeMatch(value, jsonEncoder)
            is Expr.ZoomStops -> serializeZoomStops(value, jsonEncoder)
            is Expr.Constant -> serializeConstant(value, jsonEncoder)
            is Expr.Raw -> serializeRaw(value, jsonEncoder)
            is Expr.Has<*> -> serializeHas(value as Expr.Has<T>, jsonEncoder)
            is Expr.In<*> -> serializeIn(value as Expr.In<T>, jsonEncoder)
            is Expr.Case -> serializeCase(value, jsonEncoder)
            is Expr.Coalesce -> serializeCoalesce(value, jsonEncoder)
            is Expr.At -> serializeAt(value, jsonEncoder)
            is Expr.Length<*> -> serializeLength(value as Expr.Length<T>, jsonEncoder)
            is Expr.Slice<*> -> serializeSlice(value as Expr.Slice<T>, jsonEncoder)
            is Expr.NotEquals<*, *> -> serializeNotEquals(value as Expr.NotEquals<*, *>, jsonEncoder)
            is Expr.Equals<*, *> -> serializeEquals(value as Expr.Equals<*, *>, jsonEncoder)
            is Expr.LessThan<*> -> serializeLessThan(value as Expr.LessThan<*>, jsonEncoder)
            is Expr.LessThanOrEqual<*> -> serializeLessThanOrEqual(value as Expr.LessThanOrEqual<*>, jsonEncoder)
            is Expr.GreaterThan<*> -> serializeGreaterThan(value as Expr.GreaterThan<*>, jsonEncoder)
            is Expr.GreaterThanOrEqual<*> -> serializeGreaterThanOrEqual(
                value as Expr.GreaterThanOrEqual<*>,
                jsonEncoder
            )

            is Expr.Stops -> serializeStops(value, jsonEncoder)
            is Expr.Interpolate -> serializeInterpolate(value, jsonEncoder)
            is Expr.All -> serializeAll(value, jsonEncoder)
            is Expr.AnyOf -> serializeAnyOf(value, jsonEncoder)
            is Expr.Not -> serializeNot(value, jsonEncoder)
            is Expr.Modulo -> serializeModulo(value, jsonEncoder)
            is Expr.Power -> serializePower(value, jsonEncoder)
            is Expr.Abs -> serializeAbs(value, jsonEncoder)
            is Expr.Acos -> serializeAcos(value, jsonEncoder)
            is Expr.Asin -> serializeAsin(value, jsonEncoder)
            is Expr.Atan -> serializeAtan(value, jsonEncoder)
            is Expr.Ceil -> serializeCeil(value, jsonEncoder)
            is Expr.Cos -> serializeCos(value, jsonEncoder)
            is Expr.Floor -> serializeFloor(value, jsonEncoder)
            is Expr.Ln -> serializeLn(value, jsonEncoder)
            is Expr.Log10 -> serializeLog10(value, jsonEncoder)
            is Expr.Max -> serializeMax(value, jsonEncoder)
            is Expr.Min -> serializeMin(value, jsonEncoder)
            is Expr.Round -> serializeRound(value, jsonEncoder)
            is Expr.Sin -> serializeSin(value, jsonEncoder)
            is Expr.Sqrt -> serializeSqrt(value, jsonEncoder)
            is Expr.Tan -> serializeTan(value, jsonEncoder)
            is Expr.Concat<*> -> serializeConcat(value as Expr.Concat<T>, jsonEncoder)
            is Expr.IndexOf<*> -> serializeIndexOf(value as Expr.IndexOf<T>, jsonEncoder)
            is Expr.Downcase -> serializeDowncase(value, jsonEncoder)
            is Expr.Upcase -> serializeUpcase(value, jsonEncoder)
            is Expr.ToBoolean -> serializeToBoolean(value, jsonEncoder)
            is Expr.ToString<*> -> serializeToStringExpr(value, jsonEncoder)
            is Expr.Zoom -> jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive("zoom"))))
            is Expr.Step<*> -> jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive("step"))))
            is Expr.ToNumber -> jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive("to-number"))))
            is Expr.Image -> jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive("to-number"))))
        }
    }

    private fun extractNestedExpressionChunk(from: JsonArray, startIndex: Int): Pair<JsonElement, Int> {
        if (from[startIndex] is JsonPrimitive) {
            return from[startIndex] to 1
        }
        if (from[startIndex] is JsonArray) {
            val arr = from[startIndex] as JsonArray
            return arr to 1
        }
        throw SerializationException("Unknown element type in extractNestedExpressionChunk: ${from[startIndex]}")
    }

    private fun deserializeGet(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Get<T> {
        if (element.size != 2) throw SerializationException("Invalid get expression")
        return Expr.Get(deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true))
    }

    private fun deserializeToNumber(
        element: JsonArray,
        jsonDecoder: JsonDecoder,
        isNested: Boolean
    ): Expr<Double> {
        if (element.size != 2) throw SerializationException("Invalid to-number expression")
        val inputExpr = deserializeExpr(element[1], jsonDecoder, isNested)
        return Expr.ToNumber(inputExpr)
    }

    private fun deserializeMatch(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Match<T> {
        if (element.size < 4) throw SerializationException("Invalid match expression")
        val input = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val branches = mutableListOf<Pair<List<String>, Expr<T>>>()
        var i = 2
        while (i < element.size - 1) {
            val valuesElement = element[i]
            val resultElement = element[i + 1]
            val values = when (valuesElement) {
                is JsonArray -> valuesElement.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(valuesElement.content)
                else -> throw SerializationException("Invalid match value type")
            }
            val result = deserializeExpr(
                element = resultElement,
                jsonDecoder = jsonDecoder,
                isNested = resultElement !is JsonPrimitive
            ) as Expr<T>
            branches.add(values to result)
            i += 2
        }
        val elseElement = element.last()
        val elseExpr = deserializeExpr(
            element = elseElement,
            jsonDecoder = jsonDecoder,
            isNested = elseElement !is JsonPrimitive
        ) as Expr<T>
        return Expr.Match(input, branches, elseExpr)
    }

    private fun deserializeZoomStops(
        element: JsonArray,
        jsonDecoder: JsonDecoder,
        isNested: Boolean
    ): Expr.ZoomStops<T> {
        if (element.size != 2) throw SerializationException("Invalid zoom expression")
        val stops: List<Pair<Double, Expr<*>>> = element[1].jsonArray.map { stop ->
            if (stop !is JsonArray || stop.size != 2) throw SerializationException("Invalid stop format")
            val zoom: Double = stop[0].jsonPrimitive.content.toDouble()
            val value: Expr<*> = deserializeExpr(element = stop[1], jsonDecoder = jsonDecoder, isNested = true)
            Pair(
                first = zoom,
                second = value,
            )
        }
        return Expr.ZoomStops(stops)
    }

    private fun deserializeHas(element: JsonArray): Expr.Has<T> {
        if (element.size != 2) throw SerializationException("Invalid has expression")
        return Expr.Has(element[1].jsonPrimitive.content)
    }

    private fun deserializeIn(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.In<T> {
        if (element.size != 3) throw SerializationException("Invalid in expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val array = (element[2] as JsonArray).map { it.jsonPrimitive.content }
        return Expr.In(value, array)
    }

    private fun deserializeCase(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Case<T> {
        if (element.size < 4) throw SerializationException("Invalid case expression")
        val conditions = mutableListOf<Pair<Expr<*>, Expr<T>>>()
        for (i in 1 until element.size - 1 step 2) {
            val condition = deserializeExpr(element = element[i], jsonDecoder = jsonDecoder, isNested = true)
            val result =
                deserializeExpr(element = element[i + 1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
            conditions.add(condition to result)
        }
        val default = deserializeExpr(element = element.last(), jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Case(conditions, default)
    }

    private fun deserializeCoalesce(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Coalesce<T> {
        if (element.size < 2) throw SerializationException("Invalid coalesce expression")
        val values = element.drop(1).map { valueElement ->
            deserializeExpr(element = valueElement, jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        }
        return Expr.Coalesce(values)
    }

    private fun deserializeInterpolate(
        element: JsonArray,
        jsonDecoder: JsonDecoder,
        isNested: Boolean
    ): Expr.Interpolate<T> {
        if (element.size < 4) throw SerializationException("Invalid interpolate expression")

        val interpolationType = when (val interpolationElement = element[1]) {
            is JsonPrimitive -> when (interpolationElement.content) {
                "linear" -> InterpolationType.Linear
                "exponential" -> InterpolationType.Exponential
                "cubic" -> InterpolationType.Cubic
                "step" -> InterpolationType.Step
                else -> throw SerializationException("Invalid interpolation type")
            }

            is JsonArray -> when (interpolationElement[0].jsonPrimitive.content) {
                "linear" -> InterpolationType.Linear
                "exponential" -> InterpolationType.Exponential
                "cubic" -> InterpolationType.Cubic
                "step" -> InterpolationType.Step
                else -> throw SerializationException("Invalid interpolation type")
            }

            else -> throw SerializationException("Invalid interpolation type")
        }

        val input = element[2].let { inputElement ->
            val isZoom = (inputElement is JsonPrimitive && inputElement.content == "zoom") ||
                    (inputElement is JsonArray && inputElement.size == 1 && inputElement[0].let { it is JsonPrimitive && it.content == "zoom" })
            if (isZoom) {
                Expr.Zoom
            } else if (inputElement is JsonArray && isExpr(inputElement)) {
                deserializeExpr(element = inputElement, jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
            } else {
                deserializeConstant(inputElement, jsonDecoder, true)
            }
        }

        val stops = mutableListOf<Pair<Double, Expr<T>>>()
        var i = 3
        while (i < element.size) {
            val stop = element[i++].jsonPrimitive.double
            val (valueElement, consumed) = extractNestedExpressionChunk(element, i)
            val expr = when (valueElement) {
                is JsonPrimitive -> deserializeConstant(valueElement, jsonDecoder, isNested = isNested)
                is JsonArray -> {
                    if (isExpr(valueElement)) {
                        deserializeExpr(valueElement, jsonDecoder, isNested = true) as Expr<T>
                    } else {
                        deserializeConstant(valueElement, jsonDecoder, isNested = isNested)
                    }
                }

                else -> throw SerializationException("Invalid stop value type")
            }
            stops.add(stop to expr)
            i += consumed
        }

        return Expr.Interpolate(
            interpolation = interpolationType,
            input = input,
            stops = stops
        )
    }

    private fun deserializeAt(element: JsonArray, jsonDecoder: JsonDecoder): Expr.At<T> {
        if (element.size != 3) throw SerializationException("Invalid at expression")
        val index = element[1].jsonPrimitive.content.toInt()
        val array = jsonDecoder.json.decodeFromJsonElement(valueSerializer, element[2]) as Expr<List<T>>
        return Expr.At(index, array)
    }

    private fun deserializeLength(element: JsonArray, jsonDecoder: JsonDecoder): Expr.Length<T> {
        if (element.size != 2) throw SerializationException("Invalid length expression")
        val array = jsonDecoder.json.decodeFromJsonElement(valueSerializer, element[1]) as Expr<List<T>>
        return Expr.Length(array)
    }

    private fun deserializeSlice(element: JsonArray, jsonDecoder: JsonDecoder): Expr.Slice<T> {
        if (element.size < 3 || element.size > 4) throw SerializationException("Invalid slice expression")
        val array = jsonDecoder.json.decodeFromJsonElement(valueSerializer, element[1]) as Expr<List<T>>
        val start = element[2].jsonPrimitive.content.toInt()
        val end = if (element.size == 4) element[3].jsonPrimitive.content.toInt() else null
        return Expr.Slice(array, start, end)
    }

    private fun deserializeNotEquals(
        element: JsonArray,
        jsonDecoder: JsonDecoder,
        isNested: Boolean
    ): Expr.NotEquals<*, *> {
        if (element.size != 3) throw SerializationException("Invalid not equals expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true)
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true)
        return Expr.NotEquals(left, right)
    }

    private fun deserializeEquals(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Equals<*, *> {
        if (element.size != 3) throw SerializationException("Invalid equals expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true)
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true)
        return Expr.Equals(left, right)
    }

    private fun deserializeLessThan(input: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<*> {
        if (input.size != 3) throw SerializationException("Invalid 'less than' expression format")
        return Expr.LessThan<Comparable<Any>>(
            deserializeExpr(element = input[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>,
            deserializeExpr(element = input[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>
        )
    }

    private fun deserializeLessThanOrEqual(input: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<*> {
        if (input.size != 3) throw SerializationException("Invalid 'less than or equal' expression format")
        return Expr.LessThanOrEqual<Comparable<Any>>(
            deserializeExpr(element = input[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>,
            deserializeExpr(element = input[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>
        )
    }

    private fun deserializeGreaterThan(input: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<*> {
        if (input.size != 3) throw SerializationException("Invalid 'greater than' expression format")
        return Expr.GreaterThan<Comparable<Any>>(
            deserializeExpr(element = input[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>,
            deserializeExpr(element = input[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>
        )
    }

    private fun deserializeGreaterThanOrEqual(input: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<*> {
        if (input.size != 3) throw SerializationException("Invalid 'greater than or equal' expression format")
        return Expr.GreaterThanOrEqual<Comparable<Any>>(
            deserializeExpr(element = input[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>,
            deserializeExpr(element = input[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<Comparable<Any>>
        )
    }

    private fun deserializeAll(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.All {
        if (element.size < 2) throw SerializationException("Invalid all expression")
        val conditions = element.drop(1).map { conditionElement ->
            deserializeExpr(element = conditionElement, jsonDecoder = jsonDecoder, isNested = true) as Expr<Boolean>
        }
        return Expr.All(conditions)
    }

    private fun deserializeAnyOf(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.AnyOf {
        if (element.size < 2) throw SerializationException("Invalid any expression")
        val conditions = element.drop(1).map { conditionElement ->
            deserializeExpr(element = conditionElement, jsonDecoder = jsonDecoder, isNested = true) as Expr<Boolean>
        }
        return Expr.AnyOf(conditions)
    }

    private fun deserializeNot(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Not {
        if (element.size != 2) throw SerializationException("Invalid not expression")
        val condition =
            deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<Boolean>
        return Expr.Not(condition)
    }

    private fun deserializeModulo(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Modulo {
        if (element.size != 3) throw SerializationException("Invalid modulo expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Modulo(left, right)
    }

    private fun deserializePower(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Power {
        if (element.size != 3) throw SerializationException("Invalid power expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Power(left, right)
    }

    private fun deserializeAbs(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Abs {
        if (element.size != 2) throw SerializationException("Invalid abs expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Abs(value)
    }

    private fun deserializeAcos(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Acos {
        if (element.size != 2) throw SerializationException("Invalid acos expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Acos(value)
    }

    private fun deserializeAsin(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Asin {
        if (element.size != 2) throw SerializationException("Invalid asin expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Asin(value)
    }

    private fun deserializeAtan(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Atan {
        if (element.size != 2) throw SerializationException("Invalid atan expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Atan(value)
    }

    private fun deserializeCeil(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Ceil {
        if (element.size != 2) throw SerializationException("Invalid ceil expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Ceil(value)
    }

    private fun deserializeCos(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Cos {
        if (element.size != 2) throw SerializationException("Invalid cos expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Cos(value)
    }

    private fun deserializeFloor(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Floor {
        if (element.size != 2) throw SerializationException("Invalid floor expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Floor(value)
    }

    private fun deserializeLn(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Ln {
        if (element.size != 2) throw SerializationException("Invalid ln expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Ln(value)
    }

    private fun deserializeLog10(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Log10 {
        if (element.size != 2) throw SerializationException("Invalid log10 expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Log10(value)
    }

    private fun deserializeMax(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Max {
        if (element.size != 3) throw SerializationException("Invalid max expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Max(left, right)
    }

    private fun deserializeMin(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Min {
        if (element.size != 3) throw SerializationException("Invalid min expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Min(left, right)
    }

    private fun deserializeRound(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Round {
        if (element.size != 2) throw SerializationException("Invalid round expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Round(value)
    }

    private fun deserializeSin(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Sin {
        if (element.size != 2) throw SerializationException("Invalid sin expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Sin(value)
    }

    private fun deserializeSqrt(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Sqrt {
        if (element.size != 2) throw SerializationException("Invalid sqrt expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Sqrt(value)
    }

    private fun deserializeTan(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Tan {
        if (element.size != 2) throw SerializationException("Invalid tan expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.Tan(value)
    }

    private fun deserializeConcat(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Concat<T> {
        if (element.size != 3) throw SerializationException("Invalid concat expression")
        val left = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<List<T>>
        val right = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<List<T>>
        return Expr.Concat(left, right)
    }

    private fun deserializeIndexOf(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.IndexOf<T> {
        if (element.size != 3) throw SerializationException("Invalid index-of expression")
        val array = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<List<T>>
        val value = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.IndexOf(array, value)
    }

    private fun deserializeDowncase(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Downcase {
        if (element.size != 2) throw SerializationException("Invalid downcase expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<String>
        return Expr.Downcase(value)
    }

    private fun deserializeUpcase(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Upcase {
        if (element.size != 2) throw SerializationException("Invalid upcase expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<String>
        return Expr.Upcase(value)
    }

    private fun deserializeToBoolean(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.ToBoolean {
        if (element.size != 2) throw SerializationException("Invalid to-boolean expression")
        val value = deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
        return Expr.ToBoolean(value)
    }

    private fun deserializeToString(array: JsonArray, decoder: JsonDecoder, isNested: Boolean): Expr.ToString<*> {
        require(array.size == 2) { "to-string expression must have exactly one argument" }
        val value = deserializeExpr(element = array[1], jsonDecoder = decoder, isNested = true) as Expr<T>
        return Expr.ToString(value)
    }

    private fun deserializeLiteral(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<T> {
        if (element.size != 2) throw SerializationException("Invalid literal expression")
        return deserializeExpr(element = element[1], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
    }

    private fun deserializeConstant(
        element: JsonElement,
        jsonDecoder: JsonDecoder,
        isNested: Boolean = false
    ): Expr.Constant<T> {
        if (element.toString().contains("hsl(")) {
            println("deserializeConstant with error $isNested")
        }
        return when (element) {
            is JsonPrimitive -> {
                if (isNested) {
                    if (element.isString) {
                        return Expr.Constant(element.content) as Expr.Constant<T>
                    }
                    val booleanOrNull = element.booleanOrNull
                    if (booleanOrNull != null) {
                        return Expr.Constant(booleanOrNull) as Expr.Constant<T>
                    }

                    val doubleOrNull = element.doubleOrNull
                    if (doubleOrNull != null) {
                        return Expr.Constant(doubleOrNull) as Expr.Constant<T>
                    }

                    error("cant deserialize an element $element")
                } else {
                    return Expr.Constant(jsonDecoder.json.decodeFromJsonElement(valueSerializer, element))
                }
            }

            is JsonArray -> {
                Expr.Constant(jsonDecoder.json.decodeFromJsonElement(valueSerializer, element))
            }

            else -> error("Unsupported constant type")
        } as Expr.Constant<T>
    }

    private fun deserializeImage(
        element: JsonArray,
        jsonDecoder: JsonDecoder,
        isNested: Boolean
    ): Expr<String> {
        if (element.size != 2) throw SerializationException("Invalid image expression")
        val nested = element[1]
        val inputExpr = deserializeExpr(nested, jsonDecoder, isNested = nested is JsonArray && isExpr(nested))
        return Expr.Image(inputExpr)
    }

    private fun deserializeStep(element: JsonArray, jsonDecoder: JsonDecoder, isNested: Boolean): Expr.Step<T> {
        if (element.size < 3) throw SerializationException("Invalid step expression")

        val input = when (val inputElement = element[1]) {
            is JsonArray -> {
                when (inputElement[0].jsonPrimitive.content) {
                    "zoom" -> Expr.Zoom as Expr<T>
                    else -> deserializeExpr(
                        element = inputElement,
                        jsonDecoder = jsonDecoder,
                        isNested = true
                    ) as Expr<T>
                }
            }

            is JsonPrimitive -> deserializeConstant(inputElement, jsonDecoder, true)
            else -> throw SerializationException("Invalid input expression")
        }

        val default = deserializeExpr(element = element[2], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>

        val stops = mutableListOf<Pair<Double, Expr<T>>>()
        var i = 3
        while (i < element.size - 1) {
            val stop = element[i++].jsonPrimitive.double
            val value = deserializeExpr(element = element[i++], jsonDecoder = jsonDecoder, isNested = true) as Expr<T>
            stops.add(stop to value)
        }

        return Expr.Step(input as Expr<Double>, default, stops)
    }

    fun deserializeExpr(element: JsonElement, jsonDecoder: JsonDecoder, isNested: Boolean): Expr<*> {
        println("deserializeExpr $element")
        return when (element) {
            is JsonPrimitive -> deserializeConstant(element = element, jsonDecoder = jsonDecoder, isNested = isNested)
            is JsonObject -> {
                if ("stops" in element || "type" in element || "property" in element) {
                    deserializeExpr(
                        element = normalizeLegacyExpression(element),
                        jsonDecoder = jsonDecoder,
                        isNested = true
                    )
                } else {
                    error("Unknown object expression: $element")
                }
            }

            is JsonArray -> {
                if (!isExpr(element)) return deserializeConstant(
                    element = element,
                    jsonDecoder = jsonDecoder,
                    isNested = isNested
                )
                val operator = element.firstOrNull()?.jsonPrimitive?.content

                return when (operator) {
                    "get" -> deserializeGet(element, jsonDecoder, isNested)
                    "match" -> deserializeMatch(element, jsonDecoder, isNested)
                    "zoom" -> deserializeZoomStops(element, jsonDecoder, isNested)
                    "interpolate" -> deserializeInterpolate(element, jsonDecoder, isNested)
                    "has" -> deserializeHas(element)
                    "in" -> deserializeIn(element, jsonDecoder, isNested)
                    "case" -> deserializeCase(element, jsonDecoder, isNested)
                    "coalesce" -> deserializeCoalesce(element, jsonDecoder, isNested)
                    "at" -> deserializeAt(element, jsonDecoder)
                    "length" -> deserializeLength(element, jsonDecoder)
                    "slice" -> deserializeSlice(element, jsonDecoder)
                    "!=" -> deserializeNotEquals(element, jsonDecoder, isNested)
                    "==" -> deserializeEquals(element, jsonDecoder, isNested)
                    "<" -> deserializeLessThan(element, jsonDecoder, isNested)
                    "<=" -> deserializeLessThanOrEqual(element, jsonDecoder, isNested)
                    ">" -> deserializeGreaterThan(element, jsonDecoder, isNested)
                    ">=" -> deserializeGreaterThanOrEqual(element, jsonDecoder, isNested)
                    "all" -> deserializeAll(element, jsonDecoder, isNested)
                    "any" -> deserializeAnyOf(element, jsonDecoder, isNested)
                    "!" -> deserializeNot(element, jsonDecoder, isNested)
                    "%" -> deserializeModulo(element, jsonDecoder, isNested)
                    "^" -> deserializePower(element, jsonDecoder, isNested)
                    "abs" -> deserializeAbs(element, jsonDecoder, isNested)
                    "acos" -> deserializeAcos(element, jsonDecoder, isNested)
                    "asin" -> deserializeAsin(element, jsonDecoder, isNested)
                    "atan" -> deserializeAtan(element, jsonDecoder, isNested)
                    "ceil" -> deserializeCeil(element, jsonDecoder, isNested)
                    "cos" -> deserializeCos(element, jsonDecoder, isNested)
                    "floor" -> deserializeFloor(element, jsonDecoder, isNested)
                    "ln" -> deserializeLn(element, jsonDecoder, isNested)
                    "log10" -> deserializeLog10(element, jsonDecoder, isNested)
                    "max" -> deserializeMax(element, jsonDecoder, isNested)
                    "min" -> deserializeMin(element, jsonDecoder, isNested)
                    "round" -> deserializeRound(element, jsonDecoder, isNested)
                    "sin" -> deserializeSin(element, jsonDecoder, isNested)
                    "sqrt" -> deserializeSqrt(element, jsonDecoder, isNested)
                    "tan" -> deserializeTan(element, jsonDecoder, isNested)
                    "stops" -> deserializeZoomStops(element, jsonDecoder, isNested)
                    "concat" -> deserializeConcat(element, jsonDecoder, isNested)
                    "index-of" -> deserializeIndexOf(element, jsonDecoder, isNested)
                    "downcase" -> deserializeDowncase(element, jsonDecoder, isNested)
                    "upcase" -> deserializeUpcase(element, jsonDecoder, isNested)
                    "to-boolean" -> deserializeToBoolean(element, jsonDecoder, isNested)
                    "to-string" -> deserializeToString(element, jsonDecoder, isNested)
                    "literal" -> deserializeLiteral(element, jsonDecoder, isNested)
                    "step" -> deserializeStep(element, jsonDecoder, isNested)
                    "to-number" -> deserializeToNumber(element, jsonDecoder, isNested)
                    "image" -> deserializeImage(element, jsonDecoder, isNested)
                    else -> error("unsupported expression $operator in $element")
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): Expr<T> {
        val jsonDecoder =
            decoder as? JsonDecoder ?: throw SerializationException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()

        @Suppress("UNCHECKED_CAST")
        return deserializeExpr(element = element, jsonDecoder = jsonDecoder, isNested = false) as Expr<T>
    }
} 