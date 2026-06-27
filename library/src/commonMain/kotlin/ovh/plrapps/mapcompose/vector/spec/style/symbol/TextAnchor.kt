package ovh.plrapps.mapcompose.vector.spec.style.symbol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = TextAnchorSerializer::class)
enum class TextAnchor(val value: String) {
    Center("center"),
    Left("left"),
    Right("right"),
    Top("top"),
    Bottom("bottom"),
    TopLeft("top-left"),
    TopRight("top-right"),
    BottomLeft("bottom-left"),
    BottomRight("bottom-right");

    companion object {
        fun fromString(value: String?): TextAnchor {
            return TextAnchor.entries.find { it.value.equals(value, ignoreCase = true) } ?: Center
        }

        fun fromAny(value: Any?): TextAnchor? {
            return when (value) {
                is TextAnchor -> value
                is String -> fromString(value)
                else -> null
            }
        }
    }
}

object TextAnchorSerializer : KSerializer<TextAnchor> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TextAnchor", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): TextAnchor =
        TextAnchor.fromString(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: TextAnchor) =
        encoder.encodeString(value.value)
}
