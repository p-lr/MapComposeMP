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
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.Interpolate
import ovh.plrapps.mapcompose.vector.spec.style.expression.isExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage
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
 * Evaluates a `numberArray` property, e.g. `hillshade-illumination-direction`.
 *
 * A bare scalar is a one-element array, which is upstream's backwards-compatibility rule; it is
 * applied here too so that a value that reached the engine unwrapped -- through `["get", ...]`, say
 * -- reads the same as one the parser coerced.
 */
fun ExpressionOrValue<NumberArray>?.processAsNumberArray(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): List<Double>? = NumberArray.parse(this?.processUntyped(feature, zoom))?.values

/** Evaluates a `colorArray` property, e.g. `hillshade-shadow-color`. See [processAsNumberArray]. */
fun ExpressionOrValue<ColorArray>?.processAsColorArray(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): List<Color>? = ColorArray.parse(this?.processUntyped(feature, zoom))?.values

/**
 * Evaluates a `line-gradient` at one position along a line.
 *
 * `line-gradient` is the one paint property whose expression reads a global other than `zoom`
 * (`["line-progress"]`, in `0..1` along the feature), so it cannot go through the zoom-only helpers
 * above.
 */
fun ExpressionOrValue<Color>?.processAsGradientColor(
    lineProgress: Double,
    zoom: Double? = null,
): Color? = when (this) {
    null -> null
    is ExpressionOrValue.Value -> value
    is ExpressionOrValue.Invalid -> null
    is ExpressionOrValue.Expression -> expression.styleExpression.evaluate(
        globals = GlobalProperties(zoom = zoom ?: 0.0, lineProgress = lineProgress),
        feature = null,
    ) as? Color
}

/**
 * Evaluates a `heatmap-color` at one density.
 *
 * The other global-reading paint property, alongside [processAsGradientColor]: `heatmap-color` is
 * defined over `["heatmap-density"]` in `0..1` rather than over a feature. No zoom is supplied,
 * because upstream builds the ramp once per paint value in `HeatmapStyleLayer._updateColorRamp`
 * (`renderColorRamp` passes only the density), not once per frame -- so a style that references
 * `zoom` here reads it as 0, exactly as it would in MapLibre.
 */
fun ExpressionOrValue<Color>?.processAsHeatmapColor(
    heatmapDensity: Double,
): Color? = when (this) {
    null -> null
    is ExpressionOrValue.Value -> value
    is ExpressionOrValue.Invalid -> null
    is ExpressionOrValue.Expression -> expression.styleExpression.evaluate(
        globals = GlobalProperties(zoom = 0.0, heatmapDensity = heatmapDensity),
        feature = null,
    ) as? Color
}

/**
 * Evaluates a `color-relief-color` at one elevation, in metres.
 *
 * The third global-reading colour property, alongside [processAsGradientColor] and
 * [processAsHeatmapColor]: `color-relief-color` is defined over `["elevation"]`. No zoom is
 * supplied, for the reason [processAsHeatmapColor] supplies none -- upstream builds the ramp once
 * per paint value in `ColorReliefStyleLayer._createColorRamp`, whose evaluation context carries the
 * elevation and nothing else, so a style that references `zoom` here reads it as 0 in MapLibre too.
 */
fun ExpressionOrValue<Color>?.processAsElevationColor(
    elevation: Double,
): Color? = when (this) {
    null -> null
    is ExpressionOrValue.Value -> value
    is ExpressionOrValue.Invalid -> null
    is ExpressionOrValue.Expression -> expression.styleExpression.evaluate(
        globals = GlobalProperties(zoom = 0.0, elevation = elevation),
        feature = null,
    ) as? Color
}

/**
 * The top-level `interpolate` of a `color-relief-color`, or `null` when the property is not one.
 *
 * Upstream's `expression._styleExpression.expression instanceof Interpolate`
 * (`src/style/style_layer/color_relief_style_layer.ts`), which is what decides whether a colour ramp
 * can be built at all -- see
 * [ovh.plrapps.mapcompose.vector.renderer.utils.colorReliefRamp]. No annotation node stands in the
 * way: `ParsingContext` only wraps a sub-expression whose actual type is `value` or `string`, and an
 * `interpolate` with colour outputs already types as `color`.
 */
fun ExpressionOrValue<Color>?.elevationInterpolate(): Interpolate? =
    (this as? ExpressionOrValue.Expression)?.expression?.styleExpression?.expression as? Interpolate

/**
 * Evaluates a list-valued property without coercing its items.
 *
 * `text-variable-anchor-offset` is the only user: it alternates anchor names and `[x, y]` pairs, so
 * neither [processAsStringList] nor [processAsDoubleList] can describe it.
 */
fun ExpressionOrValue<*>?.processAsAnyList(
    feature: EvalFeature? = null,
    zoom: Double? = null,
): List<Any?>? = this?.processUntyped(feature, zoom) as? List<Any?>

/**
 * Evaluates a `formatted` property -- `text-field`.
 *
 * A constant decodes to a single-section [Formatted]; a `["format", ...]` expression evaluates to a
 * multi-section one carrying per-section `text-font`, `text-size` scale and colour. A plain string
 * expression such as `["get", "name"]` is coerced by the parser, so this never sees a bare String.
 */
fun ExpressionOrValue<Formatted>?.processAsFormatted(
    feature: EvalFeature? = null,
    zoom: Double? = null,
    availableImages: List<String>? = null,
): Formatted? = when (val result = this?.processUntyped(feature, zoom, availableImages)) {
    null -> null
    is Formatted -> result
    else -> Formatted.fromString(result.toString())
}

/**
 * Evaluates a `resolvedImage` property -- `icon-image`.
 *
 * [availableImages] is what makes `["image", "a", "b"]` fall back from a missing sprite to a present
 * one: the engine marks a [ResolvedImage] `available` only when its name is in that list, exactly as
 * upstream does with the sprite atlas's key set.
 */
fun ExpressionOrValue<ResolvedImage>?.processAsImage(
    feature: EvalFeature? = null,
    zoom: Double? = null,
    availableImages: List<String>? = null,
): ResolvedImage? = when (val result = this?.processUntyped(feature, zoom, availableImages)) {
    null -> null
    is ResolvedImage -> result
    else -> ResolvedImage.fromString(result.toString())
}

/** The sprite id an `icon-image` resolves to, or `null` when it resolves to nothing. */
fun ExpressionOrValue<ResolvedImage>?.processAsImageName(
    feature: EvalFeature? = null,
    zoom: Double? = null,
    availableImages: List<String>? = null,
): String? = processAsImage(feature, zoom, availableImages)?.name?.takeIf { it.isNotEmpty() }

/**
 * Evaluates without the declared type parameter getting in the way. The generic `T` on
 * [ExpressionOrValue] is nominal — the engine works in `Any?` — so the coercing helpers above go
 * through this rather than [ExpressionOrValue.process].
 */
private fun ExpressionOrValue<*>.processUntyped(
    feature: EvalFeature?,
    zoom: Double?,
    availableImages: List<String>? = null,
): Any? =
    when (this) {
        is ExpressionOrValue.Value -> value
        is ExpressionOrValue.Invalid -> null
        is ExpressionOrValue.Expression -> expression.styleExpression.evaluate(
            globals = GlobalProperties(zoom = zoom ?: 0.0),
            feature = feature,
            availableImages = availableImages,
        )
    }

// endregion
