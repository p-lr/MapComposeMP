package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Collator
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage

/**
 * Runtime expression values.
 *
 * Ported from `maplibre-style-spec/src/expression/values.ts`. Upstream models values with a
 * `Value` union type; Kotlin keeps them as `Any?` and relies on [typeOf] for dispatch, exactly as
 * the TypeScript relies on `typeof` / `instanceof`.
 *
 * The inhabited set is:
 * `null`, [String], [Double], [Boolean], [Color], [Collator], [Formatted], [ResolvedImage],
 * `List<Any?>`, `Map<String, Any?>`.
 *
 * **Numbers are always [Double].** JavaScript has a single number type, and the whole engine's
 * equality and comparison semantics depend on that. MVT properties decode as `Int`/`Float`/`Long`/
 * `UInt`, so they are normalized through [normalizeNumbers] at the feature boundary, and JSON
 * literals are normalized when parsed. Anything entering the engine as a non-Double [Number] is a
 * bug.
 */

fun validateRGBA(r: Any?, g: Any?, b: Any?, a: Any? = null): String? {
    val rd = r as? Double
    val gd = g as? Double
    val bd = b as? Double
    if (!(rd != null && rd in 0.0..255.0 && gd != null && gd in 0.0..255.0 && bd != null && bd in 0.0..255.0)) {
        val value = if (a is Double) listOf(r, g, b, a) else listOf(r, g, b)
        return "Invalid rgba value [${value.joinToString(", ") { formatNumberForMessage(it) }}]: " +
                "'r', 'g', and 'b' must be between 0 and 255."
    }
    if (!(a == null || (a is Double && a in 0.0..1.0))) {
        return "Invalid rgba value [${
            listOf(r, g, b, a).joinToString(", ") { formatNumberForMessage(it) }
        }]: 'a' must be between 0 and 1."
    }
    return null
}

fun isValue(mixed: Any?): Boolean = when (mixed) {
    null, is String, is Boolean, is Double, is Color, is Collator, is Formatted, is ResolvedImage -> true
    is List<*> -> mixed.all { isValue(it) }
    is Map<*, *> -> mixed.all { (k, v) -> k is String && isValue(v) }
    else -> false
}

fun typeOf(value: Any?): ExprType = when (value) {
    null -> NullType
    is String -> StringType
    is Boolean -> BooleanType
    is Number -> NumberType
    is Color -> ColorType
    is Collator -> CollatorType
    is Formatted -> FormattedType
    is ResolvedImage -> ResolvedImageType
    is List<*> -> {
        var itemType: ExprType? = null
        for (item in value) {
            val t = typeOf(item)
            if (itemType == null) {
                itemType = t
            } else if (itemType == t) {
                continue
            } else {
                itemType = ValueType
                break
            }
        }
        array(itemType ?: ValueType, value.size)
    }

    else -> ObjectType
}

fun valueToString(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is Boolean -> value.toString()
    is Number -> formatNumber(value.toDouble())
    is Color -> colorToRgbaString(value)
    is Formatted, is ResolvedImage, is Collator -> value.toString()
    else -> valueToJsonString(value)
}

/** Matches `JSON.stringify` closely enough for the error messages and `to-string` outputs. */
fun valueToJsonString(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${escapeJson(value)}\""
    is Boolean -> value.toString()
    is Number -> formatNumber(value.toDouble())
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { valueToJsonString(it) }
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        "\"${escapeJson(k.toString())}\":${valueToJsonString(v)}"
    }

    is Color -> "\"${colorToRgbaString(value)}\""
    else -> "\"${escapeJson(value.toString())}\""
}

private fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length + 2)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Renders a Double the way JavaScript's `String(number)` does: integral values lose the `.0`.
 * The engine's error messages and `to-string` results are compared against upstream fixtures, so
 * this matters.
 */
fun formatNumber(d: Double): String = when {
    d.isNaN() -> "NaN"
    d == Double.POSITIVE_INFINITY -> "Infinity"
    d == Double.NEGATIVE_INFINITY -> "-Infinity"
    d == 0.0 -> "0"
    d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e21 -> d.toLong().toString()
    else -> d.toString()
}

private fun formatNumberForMessage(v: Any?): String = if (v is Number) formatNumber(v.toDouble()) else v.toString()

fun colorToRgbaString(color: Color): String {
    val r = kotlin.math.round(color.red * 255.0).toInt()
    val g = kotlin.math.round(color.green * 255.0).toInt()
    val b = kotlin.math.round(color.blue * 255.0).toInt()
    val a = color.alpha.toDouble()
    return "rgba($r,$g,$b,${formatNumber(kotlin.math.round(a * 1000.0) / 1000.0)})"
}

/**
 * Deep structural equality, ported from `maplibre-style-spec/src/util/deep_equal.ts`.
 * Used by `==` / `!=` / `match` / `in` / `index-of`.
 */
fun deepEqual(a: Any?, b: Any?): Boolean {
    if (a is List<*>) {
        if (b !is List<*> || a.size != b.size) return false
        for (i in a.indices) {
            if (!deepEqual(a[i], b[i])) return false
        }
        return true
    }
    if (a is Map<*, *>) {
        if (b !is Map<*, *> || a.size != b.size) return false
        for ((k, v) in a) {
            if (!b.containsKey(k)) return false
            if (!deepEqual(v, b[k])) return false
        }
        return true
    }
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    return a == b
}

/**
 * Coerces every [Number] in a value tree to [Double], so the engine sees JavaScript-like numbers.
 * Applied to MVT feature properties and to JSON literals.
 */
fun normalizeNumbers(value: Any?): Any? = when (value) {
    is Double -> value
    is Number -> value.toDouble()
    is UInt -> value.toDouble()
    is ULong -> value.toDouble()
    is List<*> -> value.map { normalizeNumbers(it) }
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalizeNumbers(v) }
    else -> value
}
