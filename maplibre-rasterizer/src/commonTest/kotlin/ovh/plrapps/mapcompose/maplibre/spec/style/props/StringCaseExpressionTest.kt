package ovh.plrapps.mapcompose.maplibre.spec.style.props

import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StringCaseExpressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test downcase expression with constant string`() {
        val expr = Expr.Downcase(Expr.Constant("Hello World"))
        val result = expr.evaluate(emptyMap(), null)
        assertEquals("hello world", result)
    }

    @Test
    fun `test downcase expression with get string`() {
        val expr = Expr.Downcase(Expr.Get(Expr.Constant("name")))
        val result = expr.evaluate(mapOf("name" to "Hello World"), null)
        assertEquals("hello world", result)
    }

    @Test
    fun `test downcase expression with null value`() {
        val expr = Expr.Downcase(Expr.Get(Expr.Constant("name")))
        val result = expr.evaluate(emptyMap(), null)
        assertNull(result)
    }

    @Test
    fun `test upcase expression with constant string`() {
        val expr = Expr.Upcase(Expr.Constant("Hello World"))
        val result = expr.evaluate(emptyMap(), null)
        assertEquals("HELLO WORLD", result)
    }

    @Test
    fun `test upcase expression with get string`() {
        val expr = Expr.Upcase(Expr.Get(Expr.Constant("name")))
        val result = expr.evaluate(mapOf("name" to "Hello World"), null)
        assertEquals("HELLO WORLD", result)
    }

    @Test
    fun `test upcase expression with null value`() {
        val expr = Expr.Upcase(Expr.Get(Expr.Constant("name")))
        val result = expr.evaluate(emptyMap(), null)
        assertNull(result)
    }

    @Test
    fun `test serialization and deserialization of downcase expression`() {
        val original = ExpressionOrValue.Expression(Expr.Downcase(Expr.Constant("Hello World")), source="""["downcase","Hello World"]""")
        val serializer = ExpressionOrValueSerializer(String.serializer())
        val jsonString = json.encodeToString(serializer, original)
        val deserialized = json.decodeFromString(serializer, jsonString)
        assertEquals(original, deserialized)
    }

    @Test
    fun `test serialization and deserialization of upcase expression`() {
        val original = ExpressionOrValue.Expression(Expr.Upcase(Expr.Constant("Hello World")), source = """["upcase","Hello World"]""")
        val serializer = ExpressionOrValueSerializer(String.serializer())
        val jsonString = json.encodeToString(serializer, original)
        val deserialized = json.decodeFromString(serializer, jsonString)
        assertEquals(original, deserialized)
    }
} 