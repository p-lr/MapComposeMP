package ovh.plrapps.mapcompose.vector.spec.style.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ResolvedImage

/**
 * Decodes a constant `resolvedImage` property -- `icon-image`, and the `*-pattern` properties if
 * they are ever typed this way.
 *
 * The counterpart of [FormattedSerializer]: it exists so
 * [ExpressionOrValueSerializer.expectedTypeOf] can report `ResolvedImageType` and an
 * `["image", ...]` expression compiles instead of being rejected as not a `string`.
 *
 * `available` is left false here. Whether the sprite sheet actually holds the image is decided at
 * evaluation time, from the `availableImages` list threaded through
 * [ovh.plrapps.mapcompose.vector.spec.style.props.processAsImage].
 */
object ResolvedImageSerializer : KSerializer<ResolvedImage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ResolvedImage", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ResolvedImage) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ResolvedImage =
        ResolvedImage.fromString(decoder.decodeString())
            ?: throw SerializationException("icon-image must not be empty")
}
