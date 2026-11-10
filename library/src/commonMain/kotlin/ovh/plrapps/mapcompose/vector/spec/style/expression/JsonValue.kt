package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * Bridges kotlinx-serialization's [JsonElement] and the engine's plain-Kotlin value model.
 *
 * The expression parser works on `Any?` because MapLibre's parser works on plain JavaScript values.
 * Numbers become [Double] on the way in, so the engine sees JavaScript-like numbers throughout —
 * see the note on [normalizeNumbers].
 */
fun jsonToValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        else -> element.booleanOrNull ?: element.doubleOrNull ?: element.content
    }

    is JsonArray -> element.map { jsonToValue(it) }
    is JsonObject -> element.entries.associate { (k, v) -> k to jsonToValue(v) }
}

/** The inverse of [jsonToValue], used when re-serializing an expression from its parsed form. */
fun valueToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is List<*> -> buildJsonArray { value.forEach { add(valueToJson(it)) } }
    is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), valueToJson(v)) } }
    else -> JsonPrimitive(value.toString())
}
