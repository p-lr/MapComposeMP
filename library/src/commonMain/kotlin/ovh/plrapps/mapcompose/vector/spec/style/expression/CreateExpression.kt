package ovh.plrapps.mapcompose.vector.spec.style.expression

import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Coalesce
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.GlobalState
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Interpolate
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.InterpolationType
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Let
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Step
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.expressions

/**
 * The outcome of compiling an expression: either a usable expression or the list of parse errors.
 *
 * Ported from `Result` in `maplibre-style-spec/src/util/result.ts`. Parsing never throws — callers
 * decide whether to surface the errors or fall back.
 */
sealed class ExpressionResult<out T> {
    data class Success<T>(val value: T) : ExpressionResult<T>()
    data class Error(val errors: List<ExpressionParsingError>) : ExpressionResult<Nothing>()

    val valueOrNull: T? get() = (this as? Success)?.value
}

/** Whether a JSON value looks like an expression, i.e. an array headed by a known operator name. */
fun isExpression(expression: Any?): Boolean =
    expression is List<*> && expression.isNotEmpty() &&
            expression[0] is String && expression[0] in expressions

/**
 * Parses and type-checks a style-spec JSON expression.
 *
 * Ported from `createExpression`. [rootKey] identifies the expression's location in the style JSON
 * (e.g. `layers[3].paint.line-width`) and is used to prefix runtime warnings.
 */
fun createExpression(
    expression: Any?,
    rootKey: String,
    propertySpec: StylePropertySpec? = null,
    globalState: Map<String, Any?>? = null,
): ExpressionResult<StyleExpression> {
    require(rootKey.isNotEmpty()) {
        "rootKey must identify the location of the expression in the style JSON, " +
                "e.g. \"layers[3].paint.line-width\"."
    }

    val parser = ParsingContext(
        registry = expressions,
        isConstantFunc = ::isExpressionConstant,
        expectedType = propertySpec?.expectedType,
    )

    // For string-valued properties, coerce to string at the top level rather than asserting.
    val parsed = parser.parse(
        expression,
        typeAnnotation = if (propertySpec?.isStringProperty == true) {
            ParsingContext.TypeAnnotation.COERCE
        } else {
            null
        },
    ) ?: return ExpressionResult.Error(parser.errors.toList())

    return ExpressionResult.Success(StyleExpression(parsed, rootKey, propertySpec, globalState))
}

/** How a compiled property expression varies. Ported from `EvaluationKind`. */
enum class EvaluationKind { CONSTANT, SOURCE, CAMERA, COMPOSITE }

/**
 * A compiled style property expression, with the static analysis that tells the renderer how often
 * it needs re-evaluating.
 *
 * Ported from `ZoomConstantExpression` / `ZoomDependentExpression`. The two upstream classes are
 * merged here because their evaluate methods are identical; [zoomStops] and [interpolationType] are
 * simply null for the zoom-constant kinds.
 */
class StylePropertyExpression(
    val kind: EvaluationKind,
    val styleExpression: StyleExpression,
    val zoomStops: List<Double>? = null,
    val interpolationType: InterpolationType? = null,
) {
    val isStateDependent: Boolean = when (kind) {
        EvaluationKind.CONSTANT, EvaluationKind.CAMERA -> false
        else -> !isStateConstant(styleExpression.expression)
    }

    val globalStateRefs: Set<String> = findGlobalStateRefs(styleExpression.expression)

    fun interpolationFactor(input: Double, lower: Double, upper: Double): Double =
        interpolationType?.let { Interpolate.interpolationFactor(it, input, lower, upper) } ?: 0.0
}

/**
 * Compiles an expression for a style property, running the zoom/feature static analysis.
 *
 * Ported from `createPropertyExpression`.
 */
fun createPropertyExpression(
    expressionInput: Any?,
    rootKey: String,
    propertySpec: StylePropertySpec,
    globalState: Map<String, Any?>? = null,
): ExpressionResult<StylePropertyExpression> {
    val expression = when (val r = createExpression(expressionInput, rootKey, propertySpec, globalState)) {
        is ExpressionResult.Error -> return r
        is ExpressionResult.Success -> r.value
    }

    val parsed = expression.expression

    val isFeatureConstantResult = isFeatureConstant(parsed)
    if (!isFeatureConstantResult && !propertySpec.supportsPropertyExpression) {
        return ExpressionResult.Error(listOf(ExpressionParsingError("", "data expressions not supported")))
    }

    val zoomConstant = isGlobalPropertyConstant(parsed, listOf("zoom"))
    if (!zoomConstant && !propertySpec.supportsZoomExpression) {
        return ExpressionResult.Error(listOf(ExpressionParsingError("", "zoom expressions not supported")))
    }

    val zoomCurve = findZoomCurve(parsed)
    if (zoomCurve is ZoomCurve.Invalid) {
        return ExpressionResult.Error(listOf(zoomCurve.error))
    }
    val curve = (zoomCurve as? ZoomCurve.Found)?.expression
    if (curve == null && !zoomConstant) {
        return ExpressionResult.Error(
            listOf(
                ExpressionParsingError(
                    "",
                    "\"zoom\" expression may only be used as input to a top-level \"step\" or " +
                            "\"interpolate\" expression.",
                )
            )
        )
    }
    if (curve is Interpolate && !propertySpec.supportsInterpolation) {
        return ExpressionResult.Error(
            listOf(ExpressionParsingError("", "\"interpolate\" expressions cannot be used with this property"))
        )
    }

    if (curve == null) {
        return ExpressionResult.Success(
            StylePropertyExpression(
                kind = if (isFeatureConstantResult) EvaluationKind.CONSTANT else EvaluationKind.SOURCE,
                styleExpression = expression,
            )
        )
    }

    val interpolationType = (curve as? Interpolate)?.interpolation
    val labels = when (curve) {
        is Interpolate -> curve.labels
        is Step -> curve.labels
        else -> emptyList()
    }

    return ExpressionResult.Success(
        StylePropertyExpression(
            kind = if (isFeatureConstantResult) EvaluationKind.CAMERA else EvaluationKind.COMPOSITE,
            styleExpression = expression,
            zoomStops = labels,
            interpolationType = interpolationType,
        )
    )
}

/** The result of scanning for the single permitted top-level zoom curve. */
sealed class ZoomCurve {
    data class Found(val expression: Expression) : ZoomCurve()
    data class Invalid(val error: ExpressionParsingError) : ZoomCurve()
    data object None : ZoomCurve()
}

/**
 * Finds the `step`/`interpolate` expression that takes `["zoom"]` as its input.
 *
 * Ported from `findZoomCurve`. `zoom` is only allowed at the top level of one such curve, so this
 * also reports the two ways that rule can be broken.
 */
fun findZoomCurve(expression: Expression): ZoomCurve {
    var result: ZoomCurve = ZoomCurve.None

    when {
        expression is Let -> result = findZoomCurve(expression.result)

        expression is Coalesce -> {
            for (arg in expression.args) {
                result = findZoomCurve(arg)
                if (result != ZoomCurve.None) break
            }
        }

        expression is Step && expression.input.isZoom() -> result = ZoomCurve.Found(expression)
        expression is Interpolate && expression.input.isZoom() -> result = ZoomCurve.Found(expression)
    }

    if (result is ZoomCurve.Invalid) return result

    expression.eachChild { child ->
        val childResult = findZoomCurve(child)
        if (childResult is ZoomCurve.Invalid) {
            result = childResult
        } else if (result == ZoomCurve.None && childResult is ZoomCurve.Found) {
            result = ZoomCurve.Invalid(
                ExpressionParsingError(
                    "",
                    "\"zoom\" expression may only be used as input to a top-level \"step\" or " +
                            "\"interpolate\" expression.",
                )
            )
        } else if (result is ZoomCurve.Found && childResult is ZoomCurve.Found &&
            (result as ZoomCurve.Found).expression !== childResult.expression
        ) {
            result = ZoomCurve.Invalid(
                ExpressionParsingError(
                    "",
                    "Only one zoom-based \"step\" or \"interpolate\" subexpression may be used in " +
                            "an expression.",
                )
            )
        }
    }

    return result
}

private fun Expression.isZoom(): Boolean = this is CompoundExpression && name == "zoom"

/** Collects the keys referenced by `global-state` expressions. Ported from `findGlobalStateRefs`. */
fun findGlobalStateRefs(expression: Expression, results: MutableSet<String> = mutableSetOf()): Set<String> {
    if (expression is GlobalState) {
        results.add(expression.stateKey)
    }
    expression.eachChild { child -> findGlobalStateRefs(child, results) }
    return results
}
