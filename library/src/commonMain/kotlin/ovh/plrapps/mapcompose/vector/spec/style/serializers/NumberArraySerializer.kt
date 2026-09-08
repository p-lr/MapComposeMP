package ovh.plrapps.mapcompose.vector.spec.style.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray

/**
 * Decodes a constant `numberArray` property -- `hillshade-illumination-direction` and
 * `-illumination-altitude`.
 *
 * Both spellings the spec allows decode: a bare number becomes a one-element array, which is the
 * backwards compatibility every style written before multidirectional hillshade relies on. As with
 * [FormattedSerializer], the other half of the point is the *type*:
 * [ExpressionOrValueSerializer.expectedTypeOf] keys off this serializer to tell the expression
 * parser the property expects `NumberArrayType`.
 *
 * Re-serialization always emits the array form, as upstream's `NumberArray.toString` does; a
 * property written as a bare number does not round-trip to one.
 */
object NumberArraySerializer : KSerializer<NumberArray> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NumberArray")

    override fun serialize(encoder: Encoder, value: NumberArray) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This serializer can only be used with Json")
        jsonEncoder.encodeJsonElement(JsonArray(value.values.map { JsonPrimitive(it) }))
    }

    override fun deserialize(decoder: Decoder): NumberArray {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()
        val raw: Any? = when (element) {
            is JsonPrimitive -> element.doubleOrNull
            is JsonArray -> element.map { it.jsonPrimitive.doubleOrNull }
            else -> null
        }
        return NumberArray.parse(raw)
            ?: throw SerializationException("Invalid numberArray $element")
    }
}
