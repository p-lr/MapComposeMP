package ovh.plrapps.mapcompose.vector.spec.style.props

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.StylePropertyExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.isExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer

/**
 * A style property value: either a constant or a compiled MapLibre expression.
 *
 * This is the public façade over the expression engine in
 * `ovh.plrapps.mapcompose.vector.spec.style.expression`. Painters only ever go through the
 * `processAs*` helpers below, which coerce the engine's `Any?` result to the type the painter
 * wants and return `null` when it cannot — letting the painter's `?: default` supply the MapLibre
 * spec default, which is how upstream's "fall back to the property default" behaviour is realised
 * here.
 *
 * [source] is the original JSON text, kept so the property can be re-serialized exactly.
 */
@Serializable(with = ExpressionOrValueSerializer::class)
sealed class ExpressionOrValue<T> {
    abstract val source: String

    data class Value<T>(val value: T, override val source: String = "") : ExpressionOrValue<T>()

    data class Expression<T>(
        val expression: StylePropertyExpression,
        override val source: String = "",
    ) : ExpressionOrValue<T>()

    /**
     * A property whose expression failed to compile.
     *
     * It always evaluates to `null`, so the painter's `?: default` supplies the MapLibre spec
     * default and the layer keeps rendering. The parse errors are in
     * `MapLibreConfiguration.diagnostics`.
     */
    data class Invalid<T>(override val source: String = "") : ExpressionOrValue<T>()

    /**
     * Evaluates the property.
     *
     * Never throws: a runtime error inside the expression is warned about once and yields the
     * property's default (usually `null` here). See `StyleExpression.evaluate`.
     */
    @Suppress("UNCHECKED_CAST")
    fun process(
        feature: EvalFeature? = null,
        zoom: Double? = null,
        canonical: CanonicalTileId? = null,
        availableImages: List<String>? = null,
    ): T? = when (this) {
        is Value -> value
        is Invalid -> null
        is Expression -> expression.styleExpression.evaluate(
            globals = GlobalProperties(zoom = zoom ?: 0.0),
            feature = feature,
            canonical = canonical,
            availableImages = availableImages,
        ) as T?
    }

    companion object {
        /**
         * Whether a JSON value should be parsed as an expression rather than a constant.
         *
         * An array headed by a known operator name is an expression; a legacy v7 *function object*
         * (`{stops}` / `{type}` / `{base}` / `{property}`) is normalized into one before parsing.
         */
        fun isExpression(input: JsonElement): Boolean = when (input) {
            is JsonArray -> {
                val head = input.firstOrNull()
                head is JsonPrimitive && head.isString && isExpression(jsonToValue(input))
            }

            is JsonObject -> input.containsKey("stops") ||
                    input.containsKey("type") ||
                    input.containsKey("base") ||
                    input.containsKey("property")

            else -> false
        }
    }
}

// region typed accessors
//
// Each helper mirrors what the corresponding painter needs. They deliberately return null rather
// than throwing so the caller's `?:` can supply the MapLibre spec default.

fun ExpressionOrValue<Double>?.processAsDouble(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): Double? = (this?.processUntyped(feature, zoom) as? Number)?.toDouble()

fun ExpressionOrValue<Double>?.processAsFloat(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): Float? = (this?.processUntyped(feature, zoom) as? Number)?.toFloat()

fun ExpressionOrValue<String>?.processAsString(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): String? = when (val result = this?.processUntyped(feature, zoom)) {
    null -> null
    is String -> result
    // `format` produces a Formatted value; the symbol renderer consumes plain text today.
    is Formatted -> result.toString()
    else -> result.toString()
}

fun ExpressionOrValue<Color>?.processAsColor(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): Color? = this?.processUntyped(feature, zoom) as? Color

fun ExpressionOrValue<Boolean>?.processAsBoolean(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): Boolean? = when (val result = this?.processUntyped(feature, zoom)) {
    is Boolean -> result
    is String -> result.toBooleanStrictOrNull()
    is Number -> result.toDouble() != 0.0
    else -> null
}

fun ExpressionOrValue<List<String>>?.processAsStringList(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): List<String>? {
    val result = this?.processUntyped(feature, zoom) as? List<*> ?: return null
    return result.map { it?.toString() ?: "" }
}

fun ExpressionOrValue<List<Double>>?.processAsDoubleList(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): List<Double>? {
    val result = this?.processUntyped(feature, zoom) as? List<*> ?: return null
    return result.mapNotNull { (it as? Number)?.toDouble() }
}

/**
 * Evaluates without the declared type parameter getting in the way. The generic `T` on
 * [ExpressionOrValue] is nominal — the engine works in `Any?` — so the coercing helpers above go
 * through this rather than [ExpressionOrValue.process].
 */
private fun ExpressionOrValue<*>.processUntyped(feature: EvalFeature?, zoom: Double?): Any? =
    when (this) {
        is ExpressionOrValue.Value -> value
        is ExpressionOrValue.Invalid -> null
        is ExpressionOrValue.Expression -> expression.styleExpression.evaluate(
            globals = GlobalProperties(zoom = zoom ?: 0.0),
            feature = feature,
        )
    }

// endregion
