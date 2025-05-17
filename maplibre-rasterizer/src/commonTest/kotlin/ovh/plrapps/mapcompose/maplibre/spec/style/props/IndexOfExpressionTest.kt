package ovh.plrapps.mapcompose.maplibre.spec.style.props

import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexOfExpressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test index-of expression with constant array`() {
        val expr = Expr.IndexOf<String>(
            array = Expr.Constant(listOf("a", "b", "c")),
            value = Expr.Constant("b")
        )
        
        val result = expr.evaluate(emptyMap(), null)
        assertEquals(1, result)
    }

    @Test
    fun `test index-of expression with get array`() {
        val properties = mapOf(
            "array" to listOf("x", "y", "z"),
            "value" to "y"
        )

        val expr = Expr.IndexOf<String>(
            array = Expr.Get<List<String>>(Expr.Constant("array")),
            value = Expr.Get<String>(Expr.Constant("value"))
        )
        
        val result = expr.evaluate(properties, null)
        assertEquals(1, result)
    }

    @Test
    fun `test index-of expression with non-existent value`() {
        val expr = Expr.IndexOf<String>(
            array = Expr.Constant(listOf("a", "b", "c")),
            value = Expr.Constant("d")
        )
        
        val result = expr.evaluate(emptyMap(), null)
        assertEquals(-1, result)
    }

    @Test
    fun `test index-of expression with null values`() {
        val expr = Expr.IndexOf<String>(
            array = Expr.Get<List<String>>(Expr.Constant("nonexistent")),
            value = Expr.Constant("value")
        )
        
        val result = expr.evaluate(emptyMap(), null)
        assertEquals(null, result)
    }

    @Test
    fun `test index-of expression deserialization`() {
        val jsonStr = """["index-of",["get","array"],"value"]"""
        val deserialized = json.decodeFromString(
            ExpressionOrValueSerializer(Int.serializer()),
            jsonStr
        )
        
        assertTrue(deserialized is ExpressionOrValue.Expression<*>)
        val expression = deserialized as ExpressionOrValue.Expression<Int>
        assertTrue(expression.expr is Expr.IndexOf<*>)
        
        val indexOfExpr = expression.expr as Expr.IndexOf<String>
        assertTrue(indexOfExpr.array is Expr.Get<*>)
        assertTrue(indexOfExpr.value is Expr.Constant<*>)
        assertEquals("array", ((indexOfExpr.array as Expr.Get<*>).property as Expr.Constant).value)
        assertEquals("value", (indexOfExpr.value as Expr.Constant<*>).value)
    }
} 