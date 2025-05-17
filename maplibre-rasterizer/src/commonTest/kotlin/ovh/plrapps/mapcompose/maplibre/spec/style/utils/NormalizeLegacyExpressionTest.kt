package ovh.plrapps.mapcompose.maplibre.spec.style.utils

import ovh.plrapps.mapcompose.maplibre.data.json
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizeLegacyExpressionTest {
    
    @Test
    fun `test stops-only shorthand interpolation`() {
        val input = JsonObject(mapOf(
            "stops" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(14), JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0.5))))),
                JsonArray(listOf(JsonPrimitive(18), JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0.25)))))
            ))
        ))

        val expected = JsonArray(listOf(
            JsonPrimitive("interpolate"),
            JsonArray(listOf(JsonPrimitive("linear"))),
            JsonPrimitive("zoom"),
            JsonPrimitive(14),
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0.5))),
            JsonPrimitive(18),
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0.25)))
        ))

        val result = normalizeLegacyExpression(input)
        assertEquals(expected, result)
    }

    @Test
    fun `test identity expression`() {
        val input = JsonObject(mapOf(
            "type" to JsonPrimitive("identity"),
            "property" to JsonPrimitive("color")
        ))

        val expected = JsonArray(listOf(
            JsonPrimitive("get"),
            JsonPrimitive("color")
        ))

        val result = normalizeLegacyExpression(input)
        assertEquals(expected, result)
    }

    @Test
    fun `test exponential interpolation`() {
        val input = JsonObject(mapOf(
            "type" to JsonPrimitive("exponential"),
            "property" to JsonPrimitive("population"),
            "base" to JsonPrimitive(1.5),
            "stops" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))),
                JsonArray(listOf(JsonPrimitive(1000000), JsonPrimitive(1)))
            ))
        ))

        val expected = JsonArray(listOf(
            JsonPrimitive("interpolate"),
            JsonArray(listOf(
                JsonPrimitive("exponential"),
                JsonPrimitive(1.5)
            )),
            JsonArray(listOf(
                JsonPrimitive("get"),
                JsonPrimitive("population")
            )),
            JsonPrimitive(0),
            JsonPrimitive(0),
            JsonPrimitive(1000000),
            JsonPrimitive(1)
        ))

        val result = normalizeLegacyExpression(input)
        assertEquals(expected, result)
    }

    @Test
    fun `test interval expression`() {
        val input = JsonObject(mapOf(
            "type" to JsonPrimitive("interval"),
            "property" to JsonPrimitive("population"),
            "default" to JsonPrimitive("gray"),
            "stops" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive(0), JsonPrimitive("red"))),
                JsonArray(listOf(JsonPrimitive(1000000), JsonPrimitive("yellow"))),
                JsonArray(listOf(JsonPrimitive(5000000), JsonPrimitive("green")))
            ))
        ))

        val expected = buildJsonArray {
            add("step")
            add(buildJsonArray {
                add("get")
                add("population")
            })
            add("gray")
            add(0)
            add("red")
            add(1_000_000)
            add("yellow")
            add(5_000_000)
            add("green")
        }

        val result = normalizeLegacyExpression(input)
        assertEquals(expected, result)
    }
    @Test

    fun `test zoom-based stops-only shorthand`() {
        val input = """{"stops":[[14,[1,0.5]],[18,[1,0.25]]]}"""
        val element = json.decodeFromString<JsonObject>(input)
        val expected = buildJsonArray {
            add("interpolate")
            add(buildJsonArray { add("linear") })
            add("zoom")
            add(14)
            add(buildJsonArray {
                add(1)
                add(0.5)
            })
            add(18)
            add(buildJsonArray {
                add(1)
                add(0.25)
            })
        }

        val result = normalizeLegacyExpression(element)
        assertEquals(expected, result)
    }

    @Test
    fun `test categorical expression`() {
        val input = JsonObject(mapOf(
            "type" to JsonPrimitive("categorical"),
            "property" to JsonPrimitive("type"),
            "stops" to JsonArray(listOf(
                JsonArray(listOf(JsonPrimitive("residential"), JsonPrimitive("red"))),
                JsonArray(listOf(JsonPrimitive("commercial"), JsonPrimitive("blue")))
            ))
        ))

        val expected = JsonArray(listOf(
            JsonPrimitive("match"),
            JsonArray(listOf(
                JsonPrimitive("get"),
                JsonPrimitive("type")
            )),
            JsonPrimitive("residential"),
            JsonPrimitive("red"),
            JsonPrimitive("commercial"),
            JsonPrimitive("blue"),
            JsonPrimitive(null)
        ))

        val result = normalizeLegacyExpression(input)
        assertEquals(expected, result)
    }
} 