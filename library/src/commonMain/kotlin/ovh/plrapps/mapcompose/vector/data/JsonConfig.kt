package ovh.plrapps.mapcompose.vector.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.contextual
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ColorSerializer
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchorSerializer

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
    serializersModule = kotlinx.serialization.modules.SerializersModule {
        contextual(ColorSerializer)
        contextual(TextAnchorSerializer)
    }
} 