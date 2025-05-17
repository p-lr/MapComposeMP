package ovh.plrapps.mapcompose.maplibre.spec.style.props

import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcatExpressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test concat expression with constant arrays`() {
        val expr = Expr.Concat<String>(
            left = Expr.Constant(listOf("a", "b")),
            right = Expr.Constant(listOf("c", "d"))
        )
        
        val result = expr.evaluate(emptyMap(), null)
        assertTrue(result is List<*>)
        assertEquals(4, result.size)
        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun `test concat expression with get arrays`() {
        val properties = mapOf(
            "array1" to listOf("x", "y"),
            "array2" to listOf("z", "w")
        )

        val expr = Expr.Concat<String>(
            left = Expr.Get(Expr.Constant("array1")),
            right = Expr.Get(Expr.Constant("array2"))
        )
        
        val result = expr.evaluate(properties, null)
        assertTrue(result is List<*>)
        assertEquals(4, result?.size)
        assertEquals(listOf("x", "y", "z", "w"), result)
    }

    @Test
    fun `test concat expression with mixed types`() {
        val properties = mapOf(
            "numbers" to listOf(1, 2),
            "strings" to listOf("a", "b")
        )

        val expr = Expr.Concat<Any>(
            left = Expr.Get(Expr.Constant("numbers")),
            right = Expr.Get(Expr.Constant("strings"))
        )
        
        val result = expr.evaluate(properties, null)
        assertTrue(result is List<*>)
        assertEquals(4, result?.size)
        assertEquals(listOf(1, 2, "a", "b"), result)
    }

    @Test
    fun `test concat expression with null values`() {
        val expr = Expr.Concat<String>(
            left = Expr.Get(Expr.Constant("nonexistent1")),
            right = Expr.Get(Expr.Constant("nonexistent2"))
        )
        
        val result = expr.evaluate(emptyMap(), null)
        assertEquals(null, result)
    }

    @Test
    fun `test concat expression deserialization`() {
        val jsonStr = """["concat",["get","array1"],["get","array2"]]"""
        val deserialized = json.decodeFromString(
            ExpressionOrValueSerializer(ListSerializer(String.serializer())),
            jsonStr
        )
        
        assertTrue(deserialized is ExpressionOrValue.Expression<*>)
        val expression = deserialized as ExpressionOrValue.Expression<List<String>>
        assertTrue(expression.expr is Expr.Concat<*>)
        
        val concatExpr = expression.expr as Expr.Concat<String>
        assertTrue(concatExpr.left is Expr.Get<*>)
        assertTrue(concatExpr.right is Expr.Get<*>)
        assertEquals("array1", ((concatExpr.left as Expr.Get<*>).property as Expr.Constant).value)
        assertEquals("array2", ((concatExpr.right as Expr.Get<*>).property as Expr.Constant).value)
    }
} 