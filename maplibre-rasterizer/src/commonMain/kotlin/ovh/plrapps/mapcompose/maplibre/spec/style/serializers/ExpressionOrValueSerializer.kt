package ovh.plrapps.mapcompose.maplibre.spec.style.serializers

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.ColorParser
import ovh.plrapps.mapcompose.maplibre.spec.style.utils.normalizeLegacyExpression
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.maplibre.spec.style.symbol.TextAnchorSerializer

@Serializer(forClass = ExpressionOrValue::class)
class ExpressionOrValueSerializer<T : Any>(
    private val valueSerializer: KSerializer<T>
) : KSerializer<ExpressionOrValue<T>> {

    private val exprSerializer = ExprSerializerFactory.create(valueSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ExpressionOrValue")

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ExpressionOrValue<T>) {
        val jsonEncoder =
            encoder as? JsonEncoder ?: throw SerializationException("This serializer can only be used with Json")

        when (value) {
            is ExpressionOrValue.Value -> {
                when (valueSerializer) {
                    is ColorSerializer -> {
                        jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(ColorParser.colorToHexString(value.value as Color)))
                    }

                    else -> {
                        jsonEncoder.encodeJsonElement(
                            jsonEncoder.json.encodeToJsonElement(
                                valueSerializer,
                                value.value
                            )
                        )
                    }
                }
            }

            is ExpressionOrValue.Expression -> {
                jsonEncoder.encodeJsonElement(jsonEncoder.json.encodeToJsonElement(exprSerializer, value.expr))
            }
        }
    }

    override fun deserialize(decoder: Decoder): ExpressionOrValue<T> {
        val jsonDecoder =
            decoder as? JsonDecoder ?: throw SerializationException("This serializer can only be used with Json")
        val element = jsonDecoder.decodeJsonElement()
        val isExpression = ExpressionOrValue.isExpression(element)
        return if (isExpression) {
            ExpressionOrValue.Expression(
                jsonDecoder.json.decodeFromJsonElement(
                    deserializer = exprSerializer,
                    element = if (element is JsonObject) normalizeLegacyExpression(element) else element
                ),
                source = element.toString()
            )
        } else {
            when (valueSerializer) {
                is ColorSerializer -> {
                    val color = ColorParser.parseColorStringOrNull(element.jsonPrimitive.content)
                        ?: throw SerializationException("Invalid color format ${element.jsonPrimitive.content}")
                    ExpressionOrValue.Value(color as T, source = element.toString())
                }

                else -> {
                    ExpressionOrValue.Value(
                        jsonDecoder.json.decodeFromJsonElement(valueSerializer, element),
                        source = element.toString()
                    )
                }
            }
        }
    }
}