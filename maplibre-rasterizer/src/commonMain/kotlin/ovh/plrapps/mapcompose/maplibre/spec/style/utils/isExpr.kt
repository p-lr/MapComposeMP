package ovh.plrapps.mapcompose.maplibre.spec.style.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal fun isExpr(jsonElement: JsonArray): Boolean {
    val first = jsonElement.firstOrNull()
    return first is JsonPrimitive && first.isString && first.content in knownExpressions
}