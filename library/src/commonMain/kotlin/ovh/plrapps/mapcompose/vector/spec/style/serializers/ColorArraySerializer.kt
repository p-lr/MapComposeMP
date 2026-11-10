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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser

/**
 * Decodes a constant `colorArray` property -- `hillshade-shadow-color` and `-highlight-color`.
 *
 * The `colorArray` counterpart of [NumberArraySerializer]: a bare colour string becomes a
 * one-element array, an array of colour strings becomes the array.
 */
object ColorArraySerializer : KSerializer<ColorArray> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ColorArray")

    override fun serialize(encoder: Encoder, value: ColorArray) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This serializer can only be used with Json")
        jsonEncoder.encodeJsonElement(
            JsonArray(value.values.map { JsonPrimitive(ColorParser.colorToHexString(it)) })
        )
    }

    override fun deserialize(decoder: Decoder): ColorArray {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()
        val raw: Any? = when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.map { it.jsonPrimitive.contentOrNull }
            else -> null
        }
        return ColorArray.parse(raw)
            ?: throw SerializationException("Invalid colorArray $element")
    }
}
