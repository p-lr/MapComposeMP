package ovh.plrapps.mapcompose.maplibre.spec.style.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

// Not sure to use. for discuss
fun normalizePotentialLiteralArray(element: JsonArray): JsonArray {
    return if (isExpr(element)) {
        element // expression
    } else {
        buildJsonArray {
            add("literal")
            add(element)
        }
    }
}