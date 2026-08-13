package ovh.plrapps.mapcompose.vector.spec.style.filter

import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionLogger
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.StyleExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.StylePropertySpec
import ovh.plrapps.mapcompose.vector.spec.style.expression.createExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.findGlobalStateRefs
import ovh.plrapps.mapcompose.vector.spec.style.expression.valueToJsonString

/**
 * A compiled layer filter.
 *
 * Ported from `maplibre-style-spec/src/feature_filter/index.ts`. A filter is nothing more than a
 * boolean expression: legacy (v7) filter syntax is rewritten to expression syntax by
 * [convertLegacyFilter], then compiled by the ordinary expression parser. That is why every
 * operator the expression engine gains is immediately available in filters.
 */
class FeatureFilter(
    private val expression: StyleExpression,
    /** Whether evaluating this filter needs decoded feature geometry (`within` / `distance`). */
    val needGeometry: Boolean,
    private val globalStateRefs: Set<String>,
) {
    fun filter(
        globals: GlobalProperties,
        feature: EvalFeature,
        canonical: CanonicalTileId? = null,
    ): Boolean = expression.evaluate(globals, feature, canonical = canonical) as? Boolean ?: false

    fun getGlobalStateRefs(): Set<String> = globalStateRefs

    companion object {
        /** A filter that accepts everything, used when a layer has no `filter`. */
        fun alwaysTrue(): FeatureFilter? = null
    }
}

/** Which of the two filter syntaxes a filter node belongs to. */
enum class FilterClassification {
    /** Only valid as an expression, e.g. `["==", ["get", "x"], 1]`. */
    EXPRESSION,

    /** Only valid as a deprecated filter, e.g. `["!in", "x", 1]`. */
    LEGACY,

    /**
     * Valid as both *and* meaning the same thing in each, e.g. `["has", "x"]` or a bare boolean.
     * Reading it either way gives the same result, so it is no evidence of which syntax the author
     * was writing, and it must never decide the syntax of the tree it sits in.
     */
    NEUTRAL,
}

private fun classifyChildren(children: List<Any?>): FilterClassification {
    var sawLegacy = false
    for (child in children) {
        when (classifyFilter(child)) {
            // A single expression-only child settles it for the whole tree.
            FilterClassification.EXPRESSION -> return FilterClassification.EXPRESSION
            FilterClassification.LEGACY -> sawLegacy = true
            FilterClassification.NEUTRAL -> Unit
        }
    }
    return if (sawLegacy) FilterClassification.LEGACY else FilterClassification.NEUTRAL
}

/**
 * Note that "parses as both" is not enough to be [FilterClassification.NEUTRAL]:
 * `["in", "name", ""]` parses as both, but legacy reads it as a property test and an expression
 * reads it as a substring test, so the two disagree. A node whose readings disagree stays legacy.
 */
fun classifyFilter(filter: Any?): FilterClassification {
    if (filter is Boolean) return FilterClassification.NEUTRAL

    if (filter !is List<*> || filter.isEmpty()) return FilterClassification.LEGACY

    return when (filter[0]) {
        "has" -> {
            if (filter.size < 2 || filter[1] == "\$id" || filter[1] == "\$type") {
                FilterClassification.LEGACY
            } else {
                // Both syntaxes read the two-element form as "this property is present"; only the
                // expression takes a third argument.
                if (filter.size == 2) FilterClassification.NEUTRAL else FilterClassification.EXPRESSION
            }
        }

        "in" -> {
            // Legacy `["in", key, ...values]` tests a property against a set, while the `in`
            // expression tests for a substring, so the scalar form is a conflict rather than a
            // neutral node.
            if (filter.size >= 3 && (filter[1] !is String || filter[2] is List<*>)) {
                FilterClassification.EXPRESSION
            } else {
                FilterClassification.LEGACY
            }
        }

        "!in", "!has", "none" -> FilterClassification.LEGACY

        "==", "!=", ">", ">=", "<", "<=" ->
            if (filter.size != 3 || filter[1] is List<*> || filter[2] is List<*>) {
                FilterClassification.EXPRESSION
            } else {
                FilterClassification.LEGACY
            }

        "any", "all" -> classifyChildren(filter.drop(1))

        else -> FilterClassification.EXPRESSION
    }
}

fun isExpressionFilter(filter: Any?): Boolean = classifyFilter(filter) != FilterClassification.LEGACY

private fun getFilterPropertyExpression(property: String): Any = when (property) {
    "\$type" -> listOf("geometry-type")
    "\$id" -> listOf("id")
    else -> listOf("get", property)
}

private fun getLegacyFilterExpressionSuggestion(filter: List<Any?>): Any? = when (filter[0]) {
    "==", "!=", "<", "<=", ">", ">=" -> {
        if (filter.size != 3 || filter[1] !is String) null
        else listOf(filter[0], getFilterPropertyExpression(filter[1] as String), filter[2])
    }

    "in", "!in" -> {
        if (filter.size < 2 || filter[1] !is String) {
            null
        } else {
            val expression = listOf(
                "in",
                getFilterPropertyExpression(filter[1] as String),
                listOf("literal", filter.drop(2)),
            )
            if (filter[0] == "!in") listOf("!", expression) else expression
        }
    }

    "has", "!has" -> {
        if (filter.size != 2 || filter[1] !is String) {
            null
        } else if (filter[1] == "\$type" || filter[1] == "\$id") {
            null
        } else {
            val expression = listOf("has", filter[1])
            if (filter[0] == "!has") listOf("!", expression) else expression
        }
    }

    else -> null
}

fun getMixedFilterMessage(filter: List<Any?>): String {
    if (filter[0] in listOf("<", "<=", ">", ">=") && filter.getOrNull(1) == "\$type") {
        return "\"\$type\" cannot be use with operator \"${filter[0]}\""
    }

    val suggestion = getLegacyFilterExpressionSuggestion(filter)
    return if (suggestion != null) {
        "Mixing deprecated filter syntax with expression syntax is not supported. " +
                "Replace ${valueToJsonString(filter)} with ${valueToJsonString(suggestion)}."
    } else {
        "Mixing deprecated filter syntax with expression syntax is not supported. " +
                "Convert ${valueToJsonString(filter)} to expression syntax."
    }
}

/** A legacy filter node found nested inside an expression-syntax filter. */
data class MixedFilterDiagnostic(val path: List<Int>, val legacyFilter: List<Any?>)

private fun checkChild(index: Int, path: List<Int>, filter: List<Any?>): MixedFilterDiagnostic? {
    val child = filter.getOrNull(index)
    if (child !is List<*>) return null
    if (!isExpressionFilter(child)) {
        return MixedFilterDiagnostic(path + index, child)
    }
    return findMixedLegacyFilter(child, path + index)
}

fun findMixedLegacyFilter(filter: Any?, path: List<Int> = emptyList()): MixedFilterDiagnostic? {
    if (filter !is List<*> || filter.isEmpty()) return null

    when (filter[0]) {
        "all", "any", "none" -> {
            for (i in 1 until filter.size) {
                checkChild(i, path, filter)?.let { return it }
            }
        }

        "!" -> checkChild(1, path, filter)?.let { return it }

        "case" -> {
            var i = 1
            while (i < filter.size - 1) {
                checkChild(i, path, filter)?.let { return it }
                i += 2
            }
        }
    }

    return null
}

fun warnAboutMixedLegacyFilter(filter: Any?, rootKey: String) {
    val diagnostic = findMixedLegacyFilter(filter) ?: return
    val path = diagnostic.path.joinToString(separator = "") { "[$it]" }
    ExpressionLogger.warn("$rootKey$path: ${getMixedFilterMessage(diagnostic.legacyFilter)}")
}

/** Whether the filter reads feature geometry, which is expensive to decode. */
fun geometryNeeded(filter: Any?): Boolean {
    if (filter !is List<*>) return false
    if (filter.getOrNull(0) == "within" || filter.getOrNull(0) == "distance") return true
    for (index in 1 until filter.size) {
        if (geometryNeeded(filter[index])) return true
    }
    return false
}

/**
 * Compiles a filter, converting legacy syntax first.
 *
 * Ported from `featureFilter`. Returns `null` for an absent filter (meaning "keep everything") and
 * an [ExpressionResult.Error] if the filter fails to compile — callers decide whether to drop the
 * layer or the filter.
 */
fun featureFilter(
    filter: Any?,
    rootKey: String,
    globalState: Map<String, Any?>? = null,
): ExpressionResult<FeatureFilter?> {
    if (filter == null) return ExpressionResult.Success(null)

    val expressionFilter = if (!isExpressionFilter(filter)) {
        convertLegacyFilter(filter)
    } else {
        warnAboutMixedLegacyFilter(filter, rootKey)
        filter
    }

    return when (
        val compiled = createExpression(expressionFilter, rootKey, StylePropertySpec.FILTER, globalState)
    ) {
        is ExpressionResult.Error -> compiled
        is ExpressionResult.Success -> ExpressionResult.Success(
            FeatureFilter(
                expression = compiled.value,
                needGeometry = geometryNeeded(expressionFilter),
                globalStateRefs = findGlobalStateRefs(compiled.value.expression),
            )
        )
    }
}

// region legacy conversion

/**
 * Rewrites a legacy (v7) filter into expression syntax.
 *
 * Ported from the `convertFilter` local to `feature_filter/index.ts` — note this is *not* the
 * exported converter in `feature_filter/convert.ts`. It emits the internal `filter-*` operators
 * (defined in the expression engine's table), which are specialised fast paths for the legacy
 * property/`$id`/`$type` tests.
 */
fun convertLegacyFilter(filter: Any?): Any? {
    if (filter == null) return true
    if (filter !is List<*>) return true
    val op = filter.getOrNull(0)
    if (filter.size <= 1) return op != "any"

    return when (op) {
        "==" -> convertComparisonOp(filter[1], filter[2], "==")
        "!=" -> convertNegation(convertComparisonOp(filter[1], filter[2], "=="))
        "<", ">", "<=", ">=" -> convertComparisonOp(filter[1], filter[2], op as String)
        "any" -> listOf("any") + filter.drop(1).map { convertLegacyFilter(it) }
        "all" -> listOf("all") + filter.drop(1).map { convertLegacyFilter(it) }
        "none" -> listOf("all") + filter.drop(1).map { convertNegation(convertLegacyFilter(it)) }
        "in" -> convertInOp(filter[1], filter.drop(2))
        "!in" -> convertNegation(convertInOp(filter[1], filter.drop(2)))
        "has" -> convertHasOp(filter[1])
        "!has" -> convertNegation(convertHasOp(filter[1]))
        else -> true
    }
}

private fun convertComparisonOp(property: Any?, value: Any?, op: String): Any = when (property) {
    "\$type" -> listOf("filter-type-$op", value)
    "\$id" -> listOf("filter-id-$op", value)
    else -> listOf("filter-$op", property, value)
}

private fun convertInOp(property: Any?, values: List<Any?>): Any {
    if (values.isEmpty()) return false
    return when (property) {
        "\$type" -> listOf("filter-type-in", listOf("literal", values))
        "\$id" -> listOf("filter-id-in", listOf("literal", values))
        else -> {
            val homogeneous = values.all { sameJsType(it, values[0]) }
            if (values.size > 200 && homogeneous) {
                listOf("filter-in-large", property, listOf("literal", values.sortedWith(JS_VALUE_ORDER)))
            } else {
                listOf("filter-in-small", property, listOf("literal", values))
            }
        }
    }
}

private fun convertHasOp(property: Any?): Any = when (property) {
    "\$type" -> true
    "\$id" -> listOf("filter-has-id")
    else -> listOf("filter-has", property)
}

private fun convertNegation(filter: Any?): Any = listOf("!", filter)

private fun sameJsType(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> true
    a is String && b is String -> true
    a is Boolean && b is Boolean -> true
    else -> a == null && b == null
}

/** Orders the homogeneous value list `filter-in-large` binary-searches. */
private val JS_VALUE_ORDER = Comparator<Any?> { a, b ->
    when {
        a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
        a is String && b is String -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> 0
    }
}

// endregion
