package ovh.plrapps.mapcompose.vector.spec.style.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.jsonToValue
import ovh.plrapps.mapcompose.vector.spec.style.filter.FeatureFilter
import ovh.plrapps.mapcompose.vector.spec.style.filter.featureFilter
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics

/**
 * Compiles a layer `filter` at deserialization time.
 *
 * A filter that fails to compile is reported to [StyleDiagnostics] and decodes to `null`, i.e. "no
 * filter". That matches MapLibre's lenient posture: an unusable filter must not hide the layer's
 * features, and it must certainly not abort loading the style.
 *
 * The original JSON is kept on [FilterHolder.source] so the filter can be re-serialized exactly;
 * the compiled expression is a type-annotated, constant-folded tree that no longer corresponds 1:1
 * to the input.
 */
object FeatureFilterSerializer : KSerializer<FilterHolder> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Filter")

    override fun deserialize(decoder: Decoder): FilterHolder {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("FeatureFilterSerializer only works with JSON")
        val element = jsonDecoder.decodeJsonElement()
        val source = element.toString()

        return when (val result = featureFilter(jsonToValue(element), rootKey = source)) {
            is ExpressionResult.Success -> FilterHolder(result.value, source)
            is ExpressionResult.Error -> {
                StyleDiagnostics.report(source, result.errors)
                FilterHolder(null, source)
            }
        }
    }

    override fun serialize(encoder: Encoder, value: FilterHolder) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("FeatureFilterSerializer only works with JSON")
        jsonEncoder.encodeJsonElement(jsonEncoder.json.parseToJsonElement(value.source))
    }
}

/**
 * A compiled [FeatureFilter] plus the JSON it came from.
 *
 * [filter] is null when the layer's filter failed to compile, which means "keep every feature".
 */
data class FilterHolder(val filter: FeatureFilter?, val source: String)
