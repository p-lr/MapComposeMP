package ovh.plrapps.mapcompose.maplibre.spec.style.serializers

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.ColorParser

object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Color", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Color) {
        encoder.encodeString(ColorParser.colorToHexString(value))
    }

    override fun deserialize(decoder: Decoder): Color {
        val string = decoder.decodeString()
        return ColorParser.parseColorStringOrNull(string) ?: error("parse color error $string")
    }
} 