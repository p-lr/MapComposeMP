package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * The handful of JavaScript coercion rules the expression spec is defined in terms of.
 *
 * MapLibre's reference implementation is JavaScript, and several operators are specified as
 * `Boolean(x)` or `Number(x)`. Reproducing those exactly is what makes the upstream conformance
 * fixtures pass, so they are isolated here rather than scattered through the definitions.
 */

/** `Boolean(x)` — JavaScript truthiness. */
fun jsTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Number -> {
        val d = value.toDouble()
        d != 0.0 && !d.isNaN()
    }

    is String -> value.isNotEmpty()
    else -> true
}

/** `Number(x)` — returns [Double.NaN] where JavaScript would. */
fun jsToNumber(value: Any?): Double = when (value) {
    null -> 0.0
    is Boolean -> if (value) 1.0 else 0.0
    is Number -> value.toDouble()
    is String -> {
        val trimmed = value.trim()
        when {
            trimmed.isEmpty() -> 0.0
            trimmed.startsWith("0x") || trimmed.startsWith("0X") ->
                trimmed.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN

            trimmed == "Infinity" || trimmed == "+Infinity" -> Double.POSITIVE_INFINITY
            trimmed == "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> trimmed.toDoubleOrNull() ?: Double.NaN
        }
    }

    is List<*> -> when {
        value.isEmpty() -> 0.0
        value.size == 1 -> jsToNumber(value[0])
        else -> Double.NaN
    }

    else -> Double.NaN
}

/**
 * `a === b` — strict equality. Arrays and objects compare by identity in JavaScript, so this is
 * deliberately *not* [deepEqual]; the legacy `filter-*` operators are specified in terms of `===`.
 */
fun jsStrictEqual(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    a is List<*> || b is List<*> || a is Map<*, *> || b is Map<*, *> -> a === b
    else -> a == b
}

/** `Array.prototype.indexOf` — strict equality, optional start index, `-1` when absent. */
fun jsIndexOf(list: List<Any?>, needle: Any?, fromIndex: Int = 0): Int {
    var start = fromIndex
    if (start < 0) start += list.size
    if (start < 0) start = 0
    for (i in start until list.size) {
        if (jsStrictEqual(list[i], needle)) return i
    }
    return -1
}

/** Normalizes JavaScript's relative slice bounds (negative counts from the end, clamped). */
fun jsSliceRange(size: Int, beginIn: Int, endIn: Int?): IntRange {
    var begin = if (beginIn < 0) size + beginIn else beginIn
    begin = begin.coerceIn(0, size)
    var end = if (endIn == null) size else if (endIn < 0) size + endIn else endIn
    end = end.coerceIn(0, size)
    return if (begin >= end) IntRange.EMPTY else begin until end
}

/** Splits a string into Unicode code points, matching JavaScript's `[...str]`. */
fun String.toCodePointStrings(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out.add(substring(i, i + 2))
            i += 2
        } else {
            out.add(c.toString())
            i++
        }
    }
    return out
}

/**
 * `String(x)` — JavaScript's string conversion.
 *
 * Distinct from `valueToString`, which is MapLibre's own value formatter and renders `null` as the
 * empty string. `String.prototype.indexOf` and friends coerce their argument with `String()`, so
 * `"abc".indexOf(null)` searches for the literal text `"null"`.
 */
fun jsStringify(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> formatNumber(value.toDouble())
    is String -> value
    is List<*> -> value.joinToString(",") { if (it == null) "" else jsStringify(it) }
    else -> value.toString()
}
