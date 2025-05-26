package ovh.plrapps.mapcompose.maplibre.data

import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ColorSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.contextual
import ovh.plrapps.mapcompose.maplibre.spec.style.symbol.TextAnchorSerializer

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