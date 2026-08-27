package ovh.plrapps.mapcompose.vector.spec.style.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted

/**
 * Decodes a constant `formatted` property -- in practice only `text-field`.
 *
 * A constant is always a plain string in a style, so it becomes a single-section [Formatted], which
 * is what upstream's `Formatted.fromString` does. The point of having a distinct serializer is the
 * *type*: [ExpressionOrValueSerializer.expectedTypeOf] keys off it to tell the expression parser
 * that this property expects `FormattedType`, which is what lets a `["format", ...]` expression
 * compile rather than fail the subtype check against `string` and be discarded.
 */
object FormattedSerializer : KSerializer<Formatted> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Formatted", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Formatted) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Formatted =
        Formatted.fromString(decoder.decodeString())
}
