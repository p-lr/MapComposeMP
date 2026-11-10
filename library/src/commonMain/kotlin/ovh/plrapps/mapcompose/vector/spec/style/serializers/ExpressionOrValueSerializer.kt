package ovh.plrapps.mapcompose.vector.spec.style.serializers

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.style.expression.ArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ColorArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ColorType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.FormattedType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberArrayType
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ResolvedImageType
import ovh.plrapps.mapcompose.vector.spec.style.expression.StringType
import ovh.plrapps.mapcompose.vector.spec.style.expression.StylePropertySpec
import ovh.plrapps.mapcompose.vector.spec.style.expression.ValueType
import ovh.plrapps.mapcompose.vector.spec.style.expression.array
import ovh.plrapps.mapcompose.vector.spec.style.expression.createPropertyExpression
import ovh.plrapps.mapcompose.vector.spec.style.expression.convertLegacyFunction
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleGlobalState

/**
 * Decodes a style property into an [ExpressionOrValue].
 *
 * Expressions are compiled by [createPropertyExpression], so they are type-checked against the
 * property's expected type at parse time — that is what lets `["get", "x"]` be used where a number
 * is required, and it is why data-driven `interpolate` works without any runtime casting.
 *
 * Parse errors are **not** thrown: they are recorded in [StyleDiagnostics] and the property decodes
 * to a `null`-valued constant, so one bad property never aborts loading the style.
 */
@OptIn(ExperimentalSerializationApi::class)
class ExpressionOrValueSerializer<T : Any>(
    private val valueSerializer: KSerializer<T>,
) : KSerializer<ExpressionOrValue<T>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExpressionOrValue")

    /**
     * The spec the property's expression is compiled against.
     *
     * Derived from [valueSerializer]'s descriptor, which is all the type system offers, unless the
     * caller supplied one outright -- see the secondary constructor. A `var` written once during
     * construction rather than a constructor parameter, because kotlinx-serialization requires a
     * generic serializer named in `@Serializable(with = ...)` to take nothing but its element
     * serializers in its *primary* constructor.
     */
    private var propertySpec: StylePropertySpec = derivedSpecFor(valueSerializer)

    /**
     * For a property whose Kotlin type does not describe it.
     *
     * The derivation sees only a serializer descriptor, which cannot say "this enum takes `visible`
     * or `none`, is not data-driven and may not read the zoom". `StylePropertySpec.FILTER` is the
     * same idea for the layer filter; `props/ExpressionOrVisibility.kt` is the one user.
     */
    constructor(valueSerializer: KSerializer<T>, spec: StylePropertySpec) : this(valueSerializer) {
        propertySpec = spec
    }

    override fun serialize(encoder: Encoder, value: ExpressionOrValue<T>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This serializer can only be used with Json")

        when (value) {
            is ExpressionOrValue.Value -> when (valueSerializer) {
                is ColorSerializer -> jsonEncoder.encodeJsonElement(
                    JsonUnquotedLiteral(ColorParser.colorToHexString(value.value as Color))
                )

                else -> jsonEncoder.encodeJsonElement(
                    jsonEncoder.json.encodeToJsonElement(valueSerializer, value.value)
                )
            }

            // Expressions round-trip through their original JSON text. The parsed form is a
            // type-annotated, constant-folded tree that no longer corresponds 1:1 to the input, so
            // re-deriving the JSON from it would not be faithful. An Invalid property round-trips
            // the same way, so a style that failed to compile still re-serializes unchanged.
            is ExpressionOrValue.Expression -> jsonEncoder.encodeJsonElement(
                jsonEncoder.json.parseToJsonElement(value.source)
            )

            is ExpressionOrValue.Invalid -> jsonEncoder.encodeJsonElement(
                jsonEncoder.json.parseToJsonElement(value.source)
            )
        }
    }

    override fun deserialize(decoder: Decoder): ExpressionOrValue<T> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()
        val source = element.toString()

        if (ExpressionOrValue.isExpression(element)) {
            // Legacy v7 function objects ({stops}, {type}, {property}, {base}) are rewritten into
            // expression syntax before parsing.
            val parsedValue = jsonToValue(element)
            val normalized = if (element is JsonObject) {
                @Suppress("UNCHECKED_CAST")
                convertLegacyFunction(parsedValue as Map<String, Any?>, propertySpec)
            } else {
                parsedValue
            }

            /* The style's `state` defaults, which are in scope only while `decodeStyle` runs. They
             * are baked into the compiled expression, as upstream bakes them in `createExpression`;
             * see `StyleGlobalState` for why they arrive through a holder rather than an argument. */
            val globalState = StyleGlobalState.defaults

            return when (
                val result = createPropertyExpression(normalized, source, propertySpec, globalState)
            ) {
                is ExpressionResult.Success -> ExpressionOrValue.Expression(result.value, source = source)
                is ExpressionResult.Error -> {
                    StyleDiagnostics.report(source, result.errors)
                    ExpressionOrValue.Invalid(source = source)
                }
            }
        }

        /* A constant of the wrong shape is a parse error like any other, not a throw: it used to
         * propagate out of the whole style decode, which `getMapLibreConfiguration` turns into a
         * `Result.failure` -- one malformed property blanking the entire map. */
        return runCatching {
            when (valueSerializer) {
                is ColorSerializer -> {
                    val color = ColorParser.parseColorStringOrNull(element.jsonPrimitive.content)
                        ?: throw SerializationException("Invalid color format ${element.jsonPrimitive.content}")
                    @Suppress("UNCHECKED_CAST")
                    ExpressionOrValue.Value(color as T, source = source)
                }

                else -> ExpressionOrValue.Value(
                    jsonDecoder.json.decodeFromJsonElement(valueSerializer, element),
                    source = source,
                )
            }
        }.getOrElse { error ->
            reportMalformedConstant(element, source, error)
            ExpressionOrValue.Invalid(source = source)
        }
    }

    /**
     * Records why a constant could not be read.
     *
     * An array headed by a string that is not a known operator reached the constant path precisely
     * *because* the operator is unknown -- `["bla"]` -- so the useful message is the parser's, not
     * the decoder's. Compiling it produces exactly upstream's
     * `Unknown expression "bla". If you wanted a literal array, use ["literal", [...]].`
     */
    private fun reportMalformedConstant(element: JsonElement, source: String, error: Throwable) {
        val head = (element as? JsonArray)?.firstOrNull() as? JsonPrimitive
        if (head != null && head.isString) {
            val result = createPropertyExpression(jsonToValue(element), source, propertySpec)
            if (result is ExpressionResult.Error) {
                StyleDiagnostics.report(source, result.errors)
                return
            }
        }
        StyleDiagnostics.report(source, error.message ?: error.toString())
    }

    private companion object {
        /** The spec a property gets when nothing but its Kotlin type is known about it. */
        fun derivedSpecFor(valueSerializer: KSerializer<*>): StylePropertySpec =
            expectedTypeOf(valueSerializer).let { expectedType ->
                StylePropertySpec(
                    expectedType = expectedType,
                    // MapLibre marks only number-, color-, numberArray-, colorArray- and
                    // number-array-valued properties as interpolatable. The flag decides whether an
                    // untyped legacy `{stops}` function becomes an `interpolate` or a `step`, so
                    // getting it wrong turns e.g. `text-field` stops into an "is not interpolatable"
                    // parse error.
                    supportsInterpolation = isInterpolatable(expectedType),
                )
            }

        /**
         * Derives the expression type a property expects from its Kotlin value serializer.
         *
         * Upstream reads this from generated style-spec metadata; MapCompose models properties as
         * data classes, so the serializer's descriptor is the available signal. A `null` result
         * means "no expected type", which disables the parser's assert/coerce annotation.
         */
        fun expectedTypeOf(serializer: KSerializer<*>): ExprType? = when (serializer) {
            is ColorSerializer -> ColorType
            is FormattedSerializer -> FormattedType
            is ResolvedImageSerializer -> ResolvedImageType
            is NumberArraySerializer -> NumberArrayType
            is ColorArraySerializer -> ColorArrayType
            else -> descriptorToType(serializer.descriptor)
        }

        fun isInterpolatable(type: ExprType?): Boolean = when (type) {
            NumberType, ColorType, NumberArrayType, ColorArrayType -> true
            is ArrayType -> type.itemType == NumberType
            else -> false
        }

        fun descriptorToType(descriptor: SerialDescriptor): ExprType? = when (descriptor.kind) {
            PrimitiveKind.STRING -> StringType
            PrimitiveKind.BOOLEAN -> BooleanType
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
                -> NumberType

            StructureKind.LIST -> {
                val itemType = runCatching { descriptorToType(descriptor.getElementDescriptor(0)) }.getOrNull()
                array(itemType ?: ValueType)
            }

            is PolymorphicKind -> null
            else -> null
        }
    }
}
