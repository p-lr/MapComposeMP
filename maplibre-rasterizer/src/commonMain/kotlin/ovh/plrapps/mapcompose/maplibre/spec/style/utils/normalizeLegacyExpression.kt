package ovh.plrapps.mapcompose.maplibre.spec.style.utils

import kotlinx.serialization.json.*

fun normalizeLegacyExpression(input: JsonObject): JsonArray {

    val type = input["type"]?.jsonPrimitive?.contentOrNull
    val property = input["property"]?.jsonPrimitive?.contentOrNull
    val stops = input["stops"] as? JsonArray
    val base = input["base"]?.jsonPrimitive?.floatOrNull

    return when {
        base != null && stops != null && property == null -> buildJsonArray {
            add("interpolate")
            add(buildJsonArray {
                add("exponential")
                add(base)
            })
            add("zoom")
            stops.forEach { stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
        }
        // identity → ["get", property]
        type == "identity" && property != null -> buildJsonArray {
            add("get")
            add(property)
        }

        // exponential → ["interpolate", ["exponential", base], ["get", property], ...stops]
        type == "exponential" && property != null && stops != null -> buildJsonArray {
            add("interpolate")
            add(buildJsonArray {
                add("exponential")
                add(JsonPrimitive(base ?: 1f))
            })
            add(buildJsonArray {
                add("get")
                add(property)
            })
            stops.forEach { stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
        }

        type == "interval" && property != null && stops != null -> buildJsonArray {
            add("step")
            add(buildJsonArray {
                add("get")
                add(property)
            })

            val default = input["default"] ?: JsonPrimitive(0)
            add(default)

            stops.forEach { stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
        }

        // categorical → ["match", ["get", property], ...]
        type == "categorical" && property != null && stops != null -> buildJsonArray {
            add("match")
            add(buildJsonArray {
                add("get")
                add(property)
            })
            stops.forEachIndexed { index, stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
            add(JsonPrimitive(null)) // default fallback
        }

        // stops-only shorthand → assume interpolate linear
        stops != null && property != null -> buildJsonArray {
            add("interpolate")
            add(buildJsonArray { add("linear") })
            add(buildJsonArray {
                add("get")
                add(property)
            })
            stops.forEach { stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
        }
        // stops-only without property → assume zoom-based interpolation
        stops != null && property == null -> buildJsonArray {
            add("interpolate")
            add(buildJsonArray { add("linear") })
            add(JsonPrimitive("zoom"))
            stops.forEach { stop ->
                if (stop is JsonArray && stop.size == 2) {
                    add(stop[0])
                    add(stop[1])
                }
            }
        }

        // only property → assume ["get", property]
        property != null -> buildJsonArray {
            add("get")
            add(property)
        }

        else -> error("unable to normalize legacy expression $input") // fallback
    }
}
