package ovh.plrapps.mapcompose.vector.spec.style.expression

import ovh.plrapps.mapcompose.vector.spec.style.expression.types.FormattedSection

/**
 * Where the engine reports runtime problems.
 *
 * Upstream calls `console.warn`; Kotlin Multiplatform has no shared logger, so this is a settable
 * hook. It is only ever called once per distinct `path|message` per expression.
 */
object ExpressionLogger {
    var warn: (String) -> Unit = { message -> println("[maplibre-expression] $message") }
}

/**
 * A parsed expression together with its error-handling behaviour.
 *
 * Ported from `StyleExpression` in `maplibre-style-spec/src/expression/index.ts`.
 *
 * [evaluate] never throws: on a runtime error it warns once per distinct message and returns the
 * property's default value. [evaluateWithoutErrorHandling] is the raw path, used by tests and by
 * callers that want to observe failures.
 */
class StyleExpression(
    val expression: Expression,
    private val rootKey: String,
    private val propertySpec: StylePropertySpec? = null,
    private val globalState: Map<String, Any?>? = null,
) {
    private val evaluator = EvaluationContext()
    private val defaultValue: Any? = propertySpec?.defaultValue
    private val enumValues: Set<String>? = propertySpec?.enumValues
    private val warningHistory = mutableSetOf<String>()

    private fun prepare(
        globals: GlobalProperties,
        feature: EvalFeature?,
        featureState: FeatureState?,
        canonical: CanonicalTileId?,
        availableImages: List<String>?,
        formattedSection: FormattedSection?,
    ) {
        evaluator.globals = if (globalState != null) globals.copy(globalState = globalState) else globals
        evaluator.feature = feature
        evaluator.featureState = featureState
        evaluator.canonical = canonical
        evaluator.availableImages = availableImages
        evaluator.formattedSection = formattedSection
    }

    fun evaluateWithoutErrorHandling(
        globals: GlobalProperties,
        feature: EvalFeature? = null,
        featureState: FeatureState? = null,
        canonical: CanonicalTileId? = null,
        availableImages: List<String>? = null,
        formattedSection: FormattedSection? = null,
    ): Any? {
        prepare(globals, feature, featureState, canonical, availableImages, formattedSection)
        return expression.evaluate(evaluator)
    }

    fun evaluate(
        globals: GlobalProperties,
        feature: EvalFeature? = null,
        featureState: FeatureState? = null,
        canonical: CanonicalTileId? = null,
        availableImages: List<String>? = null,
        formattedSection: FormattedSection? = null,
    ): Any? {
        prepare(globals, feature, featureState, canonical, availableImages, formattedSection)

        return try {
            val value = expression.evaluate(evaluator)
            if (value == null || (value is Double && value.isNaN())) {
                return defaultValue
            }
            if (enumValues != null && value !in enumValues) {
                throw RuntimeError(
                    "Expected value to be one of ${enumValues.joinToString(", ") { "\"$it\"" }}, " +
                            "but found ${valueToJsonString(value)} instead."
                )
            }
            value
        } catch (e: Exception) {
            // A non-RuntimeError throw has no location, so treat it as the root ('').
            val path = (e as? RuntimeError)?.path ?: ""
            val message = e.message ?: e.toString()
            val dedupKey = "$path|$message"
            if (warningHistory.add(dedupKey)) {
                ExpressionLogger.warn(formatRuntimeWarning(rootKey, path, message, defaultValue))
            }
            defaultValue
        }
    }

    private companion object {
        /**
         * The warning logged when an expression fails at evaluation: a `rootKey + index path`
         * location prefix, plus the fallback value being used.
         */
        fun formatRuntimeWarning(rootKey: String, path: String, message: String, defaultValue: Any?): String {
            val fallback = if (defaultValue == null) "" else " Falling back to ${valueToString(defaultValue)}."
            return "$rootKey$path: $message$fallback"
        }
    }
}
