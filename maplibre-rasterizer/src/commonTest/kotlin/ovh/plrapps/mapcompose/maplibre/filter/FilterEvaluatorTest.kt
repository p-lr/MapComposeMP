package ovh.plrapps.mapcompose.maplibre.filter

import ovh.plrapps.mapcompose.maplibre.spec.Tile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterEvaluatorTest {
    private val evaluator = FilterEvaluator()
    
    @Test
    fun `evaluate should handle equality operators`() {
        val (feature, layer) = createFeature(
            properties = mapOf(
                "name" to "street",
                "type" to "residential",
                "oneway" to true,
                "lanes" to 2
            )
        )
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("name"), JsonPrimitive("street")),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive("!="), JsonPrimitive("name"), JsonPrimitive("avenue")),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("lanes"), JsonPrimitive(2)),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("oneway"), JsonPrimitive(true)),
            feature = feature,
            layer = layer
        ))
    }
    
    @Test
    fun `evaluate should handle comparison operators`() {
        val (feature, layer) = createFeature(
            properties = mapOf(
                "lanes" to 2,
                "speed_limit" to 60.0
            )
        )
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive(">"), JsonPrimitive("lanes"), JsonPrimitive(1)),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(JsonPrimitive("<="), JsonPrimitive("speed_limit"), JsonPrimitive(60.0)),
            feature = feature,
            layer = layer
        ))
    }
    
    @Test
    fun `evaluate should handle in operator`() {
        val (feature, layer) = createFeature(
            properties = mapOf(
                "type" to "residential",
                "highway" to "primary"
            )
        )
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(
                JsonPrimitive("in"),
                JsonPrimitive("type"),
                JsonPrimitive("residential"),
                JsonPrimitive("commercial")
            ),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(
                JsonPrimitive("!in"),
                JsonPrimitive("highway"),
                JsonPrimitive("footway"),
                JsonPrimitive("cycleway")
            ),
            feature = feature,
            layer = layer
        ))
    }
    
    @Test
    fun `evaluate should handle logical operators`() {
        val (feature, layer) = createFeature(
            properties = mapOf(
                "type" to "residential",
                "oneway" to true,
                "lanes" to 2
            )
        )
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(
                JsonPrimitive("all"),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("type"), JsonPrimitive("residential"))),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("oneway"), JsonPrimitive(true)))
            ),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(
                JsonPrimitive("any"),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("type"), JsonPrimitive("commercial"))),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("lanes"), JsonPrimitive(2)))
            ),
            feature = feature,
            layer = layer
        ))
        
        assertTrue(evaluator.evaluate(
            filter = listOf<JsonElement>(
                JsonPrimitive("none"),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("type"), JsonPrimitive("commercial"))),
                JsonArray(listOf<JsonElement>(JsonPrimitive("=="), JsonPrimitive("lanes"), JsonPrimitive(3)))
            ),
            feature = feature,
            layer = layer
        ))
    }
    
    private fun createFeature(properties: Map<String, Any>): Pair<Tile.Feature, Tile.Layer> {
        val keys = properties.keys.toList()
        val values = properties.values.map { value ->
            when (value) {
                is String -> Tile.Value(stringValue = value)
                is Int -> Tile.Value(intValue = value.toLong())
                is Double -> Tile.Value(doubleValue = value)
                is Boolean -> Tile.Value(boolValue = value)
                else -> Tile.Value()
            }
        }
        
        val tags = mutableListOf<Int>()
        properties.forEach { (key, _) ->
            val keyIndex = keys.indexOf(key)
            val valueIndex = tags.size / 2
            if (keyIndex != -1) {
                tags.add(keyIndex)
                tags.add(valueIndex)
            }
        }
        
        val layer = Tile.Layer(
            version = 1,
            name = "test",
            features = emptyList(),
            keys = keys,
            values = values,
            extent = 4096
        )
        
        val feature = Tile.Feature(
            id = null,
            tags = tags,
            type = Tile.GeomType.LINESTRING,
            geometry = emptyList()
        )
        
        return Pair(feature, layer)
    }
} 