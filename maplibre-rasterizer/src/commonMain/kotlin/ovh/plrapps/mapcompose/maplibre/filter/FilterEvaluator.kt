package ovh.plrapps.mapcompose.maplibre.filter

import ovh.plrapps.mapcompose.maplibre.spec.Tile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FilterEvaluator {
    fun evaluate(filter: List<JsonElement>, feature: Tile.Feature, layer: Tile.Layer): Boolean {
        if (filter.isEmpty()) return true

        val operator = filter.first().toString().trim('"')
        val args = filter.drop(1)

        return when (operator) {
            "==" -> evaluateEquals(args, feature, layer)
            "!=" -> !evaluateEquals(args, feature, layer)
            ">" -> evaluateComparison(args, feature, layer) { a, b -> a > b }
            ">=" -> evaluateComparison(args, feature, layer) { a, b -> a >= b }
            "<" -> evaluateComparison(args, feature, layer) { a, b -> a < b }
            "<=" -> evaluateComparison(args, feature, layer) { a, b -> a <= b }
            "in" -> evaluateIn(args, feature, layer)
            "!in" -> !evaluateIn(args, feature, layer)
            "all" -> evaluateAll(args, feature, layer)
            "any" -> evaluateAny(args, feature, layer)
            "none" -> !evaluateAny(args, feature, layer)
            else -> false
        }
    }

    private fun evaluateEquals(args: List<JsonElement>, feature: Tile.Feature, layer: Tile.Layer): Boolean {
        if (args.size != 2) return false

        val property = args[0].toString().trim('"')
        val value = args[1]

        val featureValue = getFeatureProperty(property, feature, layer)
        return featureValue == value
    }

    private fun evaluateComparison(
        args: List<JsonElement>,
        feature: Tile.Feature,
        layer: Tile.Layer,
        comparison: (Double, Double) -> Boolean
    ): Boolean {
        if (args.size != 2) return false

        val property = args[0].toString().trim('"')
        val value = args[1]

        val featureValue = getFeatureProperty(property, feature, layer)
        if (featureValue !is JsonPrimitive || value !is JsonPrimitive) return false

        val featureNumber = featureValue.content.toDoubleOrNull() ?: return false
        val compareNumber = value.content.toDoubleOrNull() ?: return false

        return comparison(featureNumber, compareNumber)
    }

    private fun evaluateIn(args: List<JsonElement>, feature: Tile.Feature, layer: Tile.Layer): Boolean {
        if (args.size < 2) return false

        val property = args[0].toString().trim('"')
        val values = args.drop(1)

        val featureValue = getFeatureProperty(property, feature, layer)
        return values.contains(featureValue)
    }

    private fun evaluateAll(args: List<JsonElement>, feature: Tile.Feature, layer: Tile.Layer): Boolean {
        return args.all { arg ->
            when (arg) {
                is JsonArray -> evaluate(arg as List<JsonElement>, feature, layer)
                is JsonObject -> TODO()
                is JsonPrimitive -> evaluate(listOf(arg), feature, layer)
            }
        }
    }

    private fun evaluateAny(args: List<JsonElement>, feature: Tile.Feature, layer: Tile.Layer): Boolean {
        return args.any { arg ->
            when (arg) {
                is JsonArray -> evaluate(arg as List<JsonElement>, feature, layer)
                is JsonObject -> TODO()
                is JsonPrimitive -> evaluate(listOf(arg), feature, layer)
            }
        }
    }

    private fun getFeatureProperty(property: String, feature: Tile.Feature, layer: Tile.Layer): JsonElement? {
        val tags = feature.tags
        val keys = layer.keys
        val values = layer.values

        val propertyIndex = keys.indexOf(property)
        if (propertyIndex == -1) return null

        // We search for the value in tags (every second element is the index of the value)
        for (i in tags.indices step 2) {
            if (tags[i] == propertyIndex) {
                val valueIndex = tags[i + 1]
                if (valueIndex < values.size) {
                    val value = values[valueIndex]
                    return when {
                        value.stringValue != null -> JsonPrimitive(value.stringValue)
                        value.floatValue != null -> JsonPrimitive(value.floatValue)
                        value.doubleValue != null -> JsonPrimitive(value.doubleValue)
                        value.intValue != null -> JsonPrimitive(value.intValue)
                        value.uintValue != null -> JsonPrimitive(value.uintValue)
                        value.sintValue != null -> JsonPrimitive(value.sintValue)
                        value.boolValue != null -> JsonPrimitive(value.boolValue)
                        else -> null
                    }
                }
            }
        }

        return null
    }
} 