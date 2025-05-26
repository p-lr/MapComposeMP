package ovh.plrapps.mapcompose.maplibre.spec.style.props

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.knownExpressions
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.*

@Serializable(with = ExpressionOrValueSerializer::class)
sealed class ExpressionOrValue<T> {
    open val source: String = ""

    data class Value<T>(val value: T, override var source: String = "") : ExpressionOrValue<T>()
    data class Expression<T>(val expr: Expr<T>, override var source: String = "") : ExpressionOrValue<T>()

    fun process(
        featureProperties: Map<String, Any?>? = null,
        zoom: Double? = null
    ): T? = when (this) {
        is Value -> value
        is Expression -> expr.evaluate(featureProperties, zoom)
    }

    companion object {
        fun isExpression(input: JsonElement): Boolean {
            return when (input) {
                is JsonArray -> {
                    val head = input.firstOrNull()
                    head is JsonPrimitive && head.isString && head.content in knownExpressions
                }

                is JsonObject -> input.containsKey("stops") ||
                        input.containsKey("type") ||
                        input.containsKey("base") ||
                        input.containsKey("property")

                else -> false
            }
        }
    }
}

@Serializable
sealed class Expr<out T> {
    abstract fun evaluate(
        featureProperties: Map<String, Any?>?,
        zoom: Double?
    ): T?

    @Serializable
    data class Get<T>(val property: Expr<*>) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            val key = property.evaluate(featureProperties, zoom) as? String ?: return null
            return featureProperties?.get(key) as? T
        }
    }

    @Serializable
    data class ToNumber(val input: Expr<*>) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val value = input.evaluate(featureProperties, zoom)
            return when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        }
    }

    @Serializable
    data class Image(val input: Expr<*>) : Expr<String>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): String? {
            val value = input.evaluate(featureProperties, zoom)
            return value?.toString()
        }
    }

    @Serializable
    data class Step<T : Any>(
        val input: Expr<Double>,
        val default: Expr<T>,
        val stops: List<Pair<Double, Expr<T>>>
    ) : Expr<T>() {
        override fun evaluate(
            featureProperties: Map<String, Any?>?,
            zoom: Double?
        ): T? {
            val inputValue = input.evaluate(featureProperties, zoom)
            if (inputValue == null) return default.evaluate(featureProperties, zoom)

            var matched: Expr<T>? = null
            for ((stop, valueExpr) in stops) {
                if (inputValue < stop) break
                matched = valueExpr
            }
            return (matched ?: default).evaluate(featureProperties, zoom)
        }
    }

    @Serializable
    data class Constant<T>(val value: T) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?) = value
    }

    @Serializable
    data class Match<T>(
        val input: Expr<*>,
        val branches: List<Pair<List<String>, Expr<T>>>,
        val elseExpr: Expr<T>
    ) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            val inputValue = input.evaluate(featureProperties, zoom)?.toString()
            for ((values, expr) in branches) {
                if (inputValue != null && values.contains(inputValue)) {
                    return expr.evaluate(featureProperties, zoom)
                }
            }
            return elseExpr.evaluate(featureProperties, zoom)
        }
    }

    @Serializable
    data class ZoomStops<T>(val stops: List<Pair<Double, Expr<*>>>) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            val z: Double = zoom ?: return (stops.firstOrNull()?.second?.evaluate(featureProperties, zoom) as T?)
            return stops.lastOrNull { it.first <= z }?.second?.evaluate(featureProperties, zoom) as T?
                ?: stops.firstOrNull()?.second?.evaluate(featureProperties, zoom) as T?
        }
    }


    @Serializable
    data class Raw<T>(val json: JsonElement) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?) = null
    }

    @Serializable
    data class Has<T>(val property: String) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            return featureProperties?.containsKey(property)
        }
    }

    @Serializable
    data class In<T>(
        val value: Expr<*>,
        @Contextual
        val array: List<String>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return array.contains(evaluatedValue?.toString())
        }
    }

    @Serializable
    data class Case<T>(
        val conditions: List<Pair<Expr<*>, Expr<T>>>,
        val default: Expr<T>
    ) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            for ((condition, result) in conditions) {
                if (condition.evaluate(featureProperties, zoom) == true) {
                    return result.evaluate(featureProperties, zoom)
                }
            }
            return default.evaluate(featureProperties, zoom)
        }
    }

    @Serializable
    data class Coalesce<T>(val values: List<Expr<T>>) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            for (expr in values) {
                val result = expr.evaluate(featureProperties, zoom)
                if (result != null) {
                    return result
                }
            }
            return null
        }
    }

    @Serializable
    data class At<T>(val index: Int, val array: Expr<List<T>>) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            val arrayValue = array.evaluate(featureProperties, zoom) ?: return null
            return arrayValue.getOrNull(index)
        }
    }

    @Serializable
    data class Length<T>(val array: Expr<List<T>>) : Expr<Int>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Int? {
            val arrayValue = array.evaluate(featureProperties, zoom) ?: return null
            return arrayValue.size
        }
    }

    @Serializable
    data class Slice<T>(val array: Expr<List<T>>, val start: Int, val end: Int?) : Expr<List<T>>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): List<T>? {
            val arrayValue = array.evaluate(featureProperties, zoom) ?: return null
            val endIndex = end ?: arrayValue.size
            return arrayValue.subList(start, endIndex.coerceAtMost(arrayValue.size))
        }
    }

    @Serializable
    data class NotEquals<T, R>(
        val left: Expr<T>,
        val right: Expr<R>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return leftValue != rightValue
        }
    }

    @Serializable
    data class Equals<T, R>(
        val left: Expr<T>,
        val right: Expr<R>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return leftValue == rightValue
        }
    }

    @Serializable
    data class LessThan<T>(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> leftValue.toDouble() < rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue < rightValue
                else -> null
            }
        }
    }

    @Serializable
    data class LessThanOrEqual<T>(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> leftValue.toDouble() <= rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue <= rightValue
                else -> null
            }
        }
    }

    @Serializable
    data class GreaterThan<T>(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> leftValue.toDouble() > rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue > rightValue
                else -> null
            }
        }
    }

    @Serializable
    data class GreaterThanOrEqual<T>(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> leftValue.toDouble() >= rightValue.toDouble()
                leftValue is String && rightValue is String -> leftValue >= rightValue
                else -> null
            }
        }
    }

    @Serializable
    data class All(val conditions: List<Expr<Boolean>>) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            if (conditions.isEmpty()) return null
            for (condition in conditions) {
                val result = condition.evaluate(featureProperties, zoom)
                if (result == null || !result) return result
            }
            return true
        }
    }

    @Serializable
    data class AnyOf(val conditions: List<Expr<Boolean>>) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            if (conditions.isEmpty()) return null
            for (condition in conditions) {
                val result = condition.evaluate(featureProperties, zoom)
                if (result == true) return true
                if (result == null) return null
            }
            return false
        }
    }

    @Serializable
    data class Not(val condition: Expr<Boolean>) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val result = condition.evaluate(featureProperties, zoom)
            return result?.not()
        }
    }

    @Serializable
    data class Modulo(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> {
                    val divisor = rightValue.toDouble()
                    if (divisor == 0.0) null
                    else leftValue.toDouble() % divisor
                }

                else -> null
            }
        }
    }

    @Serializable
    data class Power(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number -> {
                    val base = leftValue.toDouble()
                    val exponent = rightValue.toDouble()
                    base.pow(exponent)
                }

                else -> null
            }
        }
    }

    @Serializable
    data class Abs(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> abs(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Acos(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> acos(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Asin(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> asin(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Atan(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> atan(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Ceil(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> ceil(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Cos(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> cos(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Floor(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> floor(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Ln(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> {
                    val doubleValue = evaluatedValue.toDouble()
                    if (doubleValue <= 0) null
                    else ln(doubleValue)
                }

                else -> null
            }
        }
    }

    @Serializable
    data class Log10(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> {
                    val doubleValue = evaluatedValue.toDouble()
                    if (doubleValue <= 0) null
                    else log10(doubleValue)
                }

                else -> null
            }
        }
    }

    @Serializable
    data class Max(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number ->
                    max(leftValue.toDouble(), rightValue.toDouble())

                else -> null
            }
        }
    }

    @Serializable
    data class Min(
        val left: Expr<*>,
        val right: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val leftValue = left.evaluate(featureProperties, zoom)
            val rightValue = right.evaluate(featureProperties, zoom)
            return when {
                leftValue == null || rightValue == null -> null
                leftValue is Number && rightValue is Number ->
                    min(leftValue.toDouble(), rightValue.toDouble())

                else -> null
            }
        }
    }

    @Serializable
    data class Round(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> round(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Sin(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> sin(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Sqrt(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> {
                    val doubleValue = evaluatedValue.toDouble()
                    if (doubleValue < 0) null
                    else sqrt(doubleValue)
                }

                else -> null
            }
        }
    }

    @Serializable
    data class Tan(
        val value: Expr<*>
    ) : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                is Number -> tan(evaluatedValue.toDouble())
                else -> null
            }
        }
    }

    @Serializable
    data class Stops<T>(val stops: List<Pair<Double, T>>) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            if (stops.isEmpty()) return null
            if (stops.size == 1) return stops.first().second

            val sortedStops = stops.sortedBy { it.first }
            val value = zoom ?: return sortedStops.first().second

            return when {
                value <= sortedStops.first().first -> sortedStops.first().second
                value >= sortedStops.last().first -> sortedStops.last().second
                else -> {
                    val index = sortedStops.indexOfLast { it.first <= value }
                    if (index < 0 || index == sortedStops.lastIndex) return null
                    val (lowerStop, lowerValue) = sortedStops[index]
                    val (upperStop, upperValue) = sortedStops[index + 1]
                    val t = (value - lowerStop) / (upperStop - lowerStop)
                    interpolate(t, lowerValue, upperValue)
                }
            }
        }
    }

    @Serializable
    data class Interpolate<T : Any>(
        val interpolation: InterpolationType,
        val input: Expr<*>,
        val stops: List<Pair<Double, Expr<T>>>
    ) : Expr<T>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): T? {
            if (stops.isEmpty()) return null
            if (stops.size == 1) return stops.first().second.evaluate(featureProperties, zoom)

            val sortedStops = stops.sortedBy { it.first }
            val inputValue = input.evaluate(featureProperties, zoom) as? Double ?: return null
            val firstStop = sortedStops.first()
            val lastStop = sortedStops.last()
            return when {
                inputValue <= firstStop.first -> firstStop.second.evaluate(featureProperties, zoom)
                inputValue >= lastStop.first -> lastStop.second.evaluate(featureProperties, zoom)
                else -> {
                    val index = sortedStops.indexOfLast { it.first <= inputValue }
                    if (index < 0 || index == sortedStops.lastIndex) return null
                    val (lowerStop, lowerExpr) = sortedStops[index]
                    val (upperStop, upperExpr) = sortedStops[index + 1]
                    val t = when (interpolation) {
                        is InterpolationType.Linear -> (inputValue - lowerStop) / (upperStop - lowerStop)
                        is InterpolationType.Exponential -> {
                            val base = interpolation.base
                            if (base == 1.0) {
                                (inputValue - lowerStop) / (upperStop - lowerStop)
                            } else {
                                (base.pow((inputValue - lowerStop) / (upperStop - lowerStop)) - 1) / (base - 1)
                            }
                        }

                        else -> (inputValue - lowerStop) / (upperStop - lowerStop) // fallback
                    }
                    val lowerValue = lowerExpr.evaluate(featureProperties, zoom) ?: return null
                    val upperValue = upperExpr.evaluate(featureProperties, zoom) ?: return null
                    interpolate(t, lowerValue, upperValue)
                }
            }
        }
    }

    @Serializable
    data class Concat<T>(
        val left: Expr<List<T>>,
        val right: Expr<List<T>>
    ) : Expr<List<T>>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): List<T>? {
            val leftValue = left.evaluate(featureProperties, zoom) ?: return null
            val rightValue = right.evaluate(featureProperties, zoom) ?: return null
            return leftValue + rightValue
        }
    }

    @Serializable
    data class IndexOf<T>(
        val array: Expr<List<T>>,
        val value: Expr<T>
    ) : Expr<Int>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Int? {
            val arrayValue = array.evaluate(featureProperties, zoom) ?: return null
            val searchValue = value.evaluate(featureProperties, zoom) ?: return null
            return arrayValue.indexOf(searchValue)
        }
    }

    @Serializable
    data class Downcase(
        val value: Expr<String>
    ) : Expr<String>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): String? {
            val evaluatedValue = value.evaluate(featureProperties, zoom) ?: return null
            return evaluatedValue.lowercase()
        }
    }

    @Serializable
    data class Upcase(
        val value: Expr<String>
    ) : Expr<String>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): String? {
            val evaluatedValue = value.evaluate(featureProperties, zoom) ?: return null
            return evaluatedValue.uppercase()
        }
    }

    @Serializable
    data class ToBoolean(
        val value: Expr<*>
    ) : Expr<Boolean>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Boolean? {
            val evaluatedValue = value.evaluate(featureProperties, zoom)
            return when (evaluatedValue) {
                null -> false
                is String -> evaluatedValue.isNotEmpty()
                is Number -> evaluatedValue.toDouble() != 0.0 && !evaluatedValue.toDouble().isNaN()
                is Boolean -> evaluatedValue
                else -> true
            }
        }
    }

    @Serializable
    data class ToString<T>(
        val value: Expr<T>
    ) : Expr<String>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): String? {
            val evaluated = value.evaluate(featureProperties, zoom)
            return when (evaluated) {
                null -> ""
                is String -> evaluated
                is Boolean -> evaluated.toString()
                is Number -> evaluated.toString()
                is Color -> "rgba(${(evaluated.red * 255).toInt()},${(evaluated.green * 255).toInt()},${(evaluated.blue * 255).toInt()},${(evaluated.alpha * 100).toInt() / 100.0})"
                is Map<*, *> -> {
                    val entries = evaluated.map { (k, v) ->
                        "\"${k}\":${
                            when (v) {
                                null -> "null"
                                is String -> "\"$v\""
                                is Number, is Boolean -> v.toString()
                                is Map<*, *>, is List<*> -> (Expr.ToString(Expr.Constant(v))).evaluate(null, null)
                                else -> "\"${v.toString()}\""
                            }
                        }"
                    }
                    "{${entries.joinToString(",")}}"
                }

                is List<*> -> {
                    val elements = evaluated.map { v ->
                        when (v) {
                            null -> "null"
                            is String -> "\"$v\""
                            is Number, is Boolean -> v.toString()
                            is Map<*, *>, is List<*> -> (Expr.ToString(Expr.Constant(v))).evaluate(null, null)
                            else -> "\"${v.toString()}\""
                        }
                    }
                    "[${elements.joinToString(",")}]"
                }

                else -> evaluated.toString()
            }
        }
    }

    @Serializable
    object Zoom : Expr<Double>() {
        override fun evaluate(featureProperties: Map<String, Any?>?, zoom: Double?): Double? = zoom
    }

    private fun <T : Number> nearest(value: Double, a: T, b: T): T =
        if (abs(value - a.toDouble()) <= abs(value - b.toDouble())) a else b

    // TODO need test
    fun <T> interpolate(t: Double, a: T, b: T): T? {
        return when {
            a is Double && b is Double -> (a * (1 - t) + b * t) as T
            a is Float && b is Float -> (a * (1 - t.toFloat()) + b * t.toFloat()) as T
            a is Int && b is Int -> (a * (1 - t) + b * t).toInt() as T
            a is Number && b is Number -> {
                val at = a.toDouble()
                val bt = b.toDouble()
                (at * (1 - t) + bt * t) as T
            }
            a is Color && b is Color -> lerp(start = a, stop = b, fraction = t.toFloat()) as T
            a is String && b is String -> if (t <= 0.5) a else b
            else -> if (t < 0.5) a else b
        }
    }
}

@Serializable
sealed class InterpolationType {
    @Serializable
    object Linear : InterpolationType()

    @Serializable
    data class Exponential(val base: Double) : InterpolationType()

    @Serializable
    object Cubic : InterpolationType()

    @Serializable
    object Step : InterpolationType()
}