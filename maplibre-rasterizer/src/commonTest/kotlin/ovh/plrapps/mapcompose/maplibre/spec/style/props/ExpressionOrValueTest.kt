package ovh.plrapps.mapcompose.maplibre.spec.style.props

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.builtins.serializer

class ExpressionOrValueTest {
    @Test
    fun `Value should return its value`() {
        val value: ExpressionOrValue<Int> = ExpressionOrValue.Value(42)
        assertEquals(42, value.process())
    }

    @Test
    fun `Expression with Get should return property value`() {
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(Expr.Get(Expr.Constant("name")))
        val properties = mapOf("name" to "test")
        assertEquals("test", expr.process(featureProperties = properties))
    }

    @Test
    fun `Expression with Match should return correct value`() {
        val matchExpr = Expr.Match(
            input = Expr.Get<String>(Expr.Constant("type")),
            branches = listOf(
                listOf("residential") to Expr.Constant("local")
            ),
            elseExpr = Expr.Constant("other")
        )
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(matchExpr)

        val matchingProps = mapOf("type" to "residential")
        assertEquals("local", expr.process(featureProperties = matchingProps))

        val nonMatchingProps = mapOf("type" to "commercial")
        assertEquals("other", expr.process(featureProperties = nonMatchingProps))
    }

    @Test
    fun `Expression with ZoomStops should return correct value`() {
        val stops = listOf(
            10.0 to Expr.Constant("small"),
            14.0 to Expr.Constant("medium"),
            18.0 to Expr.Constant("large")
        )
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(Expr.ZoomStops(stops))

        assertEquals("small", expr.process(zoom = 8.0))
        assertEquals("small", expr.process(zoom = 12.0))
        assertEquals("medium", expr.process(zoom = 16.0))
        assertEquals("large", expr.process(zoom = 20.0))
    }

    @Test
    fun `Expression with Interpolate Linear should interpolate numbers`() {
        val interpolateExpr = Expr.Interpolate<Double>(
            interpolation = InterpolationType.Linear,
            input = Expr.Zoom,
            stops = listOf(
                0.0 to Expr.Constant(0.0),
                10.0 to Expr.Constant(100.0)
            )
        )
        val expr: ExpressionOrValue<Double> = ExpressionOrValue.Expression(interpolateExpr)

        // value Interpolated
        assertEquals(50.0, expr.process(zoom = 5.0))
    }

    @Test
    fun `Expression with Constant should return its value`() {
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(Expr.Constant("test"))
        assertEquals("test", expr.process())
    }

    @Test
    fun `Expression with Raw should return null`() {
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(Expr.Raw(kotlinx.serialization.json.JsonNull))
        assertNull(expr.process())
    }

    @Test
    fun `process should handle null properties and zoom`() {
        val expr: ExpressionOrValue<String> = ExpressionOrValue.Expression(Expr.Get(Expr.Constant("name")))
        assertNull(expr.process())
        assertNull(expr.process(featureProperties = emptyMap()))
    }

    @Test
    fun testNotEqualsExpressionWithConstants() {
        val expr = Expr.NotEquals(
            left = Expr.Constant("value1"),
            right = Expr.Constant("value2")
        )
        
        val result = expr.evaluate(emptyMap(), 0.0)
        assertTrue(result == true)
        
        val expr2 = Expr.NotEquals(
            left = Expr.Constant("same"),
            right = Expr.Constant("same")
        )
        
        val result2 = expr2.evaluate(emptyMap(), 0.0)
        assertTrue(result2 == false)
    }

    @Test
    fun testNotEqualsExpressionWithGet() {
        val properties = mapOf(
            "prop1" to "value1",
            "prop2" to "value2",
            "prop3" to "value1"
        )

        val expr1 = Expr.NotEquals(
            left = Expr.Get<String>(Expr.Constant("prop1")),
            right = Expr.Get<String>(Expr.Constant("prop2"))
        )
        assertTrue(expr1.evaluate(properties, 0.0) == true)

        val expr2 = Expr.NotEquals(
            left = Expr.Get<String>(Expr.Constant("prop1")),
            right = Expr.Get<String>(Expr.Constant("prop3"))
        )
        assertTrue(expr2.evaluate(properties, 0.0) == false)
    }

    @Test
    fun testNotEqualsExpressionWithMixedTypes() {
        val properties = mapOf(
            "number" to 42,
            "text" to "42"
        )

        val expr = Expr.NotEquals(
            left = Expr.Get<Any>(Expr.Constant("number")),
            right = Expr.Get<Any>(Expr.Constant("text"))
        )
        assertTrue(expr.evaluate(properties, 0.0) == true)
    }

    @Test
    fun testNotEqualsExpressionWithNullValues() {
        val properties = mapOf(
            "existing" to "value",
            "nullValue" to null
        )

        val expr1 = Expr.NotEquals(
            left = Expr.Get<Any>(Expr.Constant("existing")),
            right = Expr.Get<Any>(Expr.Constant("nonexistent"))
        )
        assertTrue(expr1.evaluate(properties, 0.0) == true)

        val expr2 = Expr.NotEquals(
            left = Expr.Get<Any>(Expr.Constant("nullValue")),
            right = Expr.Get<Any>(Expr.Constant("nonexistent"))
        )
        assertTrue(expr2.evaluate(properties, 0.0) == false)
    }

    @Test
    fun testEqualsExpressionWithConstants() {
        val expr = Expr.Equals(
            left = Expr.Constant("value1"),
            right = Expr.Constant("value2")
        )
        
        val result = expr.evaluate(emptyMap(), 0.0)
        assertTrue(result == false)
        
        val expr2 = Expr.Equals(
            left = Expr.Constant("same"),
            right = Expr.Constant("same")
        )
        
        val result2 = expr2.evaluate(emptyMap(), 0.0)
        assertTrue(result2 == true)
    }

    @Test
    fun testEqualsExpressionWithGet() {
        val properties = mapOf(
            "prop1" to "value1",
            "prop2" to "value2",
            "prop3" to "value1"
        )

        val expr1 = Expr.Equals(
            left = Expr.Get<String>(Expr.Constant("prop1")),
            right = Expr.Get<String>(Expr.Constant("prop2"))
        )
        assertTrue(expr1.evaluate(properties, 0.0) == false)

        val expr2 = Expr.Equals(
            left = Expr.Get<String>(Expr.Constant("prop1")),
            right = Expr.Get<String>(Expr.Constant("prop3"))
        )
        assertTrue(expr2.evaluate(properties, 0.0) == true)
    }

    @Test
    fun testEqualsExpressionWithMixedTypes() {
        val properties = mapOf(
            "number" to 42,
            "text" to "42"
        )

        val expr = Expr.Equals(
            left = Expr.Get<Any>(Expr.Constant("number")),
            right = Expr.Get<Any>(Expr.Constant("text"))
        )
        assertTrue(expr.evaluate(properties, 0.0) == false)
    }

    @Test
    fun testEqualsExpressionWithNullValues() {
        val properties = mapOf(
            "existing" to "value",
            "nullValue" to null
        )

        val expr1 = Expr.Equals(
            left = Expr.Get<Any>(Expr.Constant("existing")),
            right = Expr.Get<Any>(Expr.Constant("nonexistent"))
        )
        assertTrue(expr1.evaluate(properties, 0.0) == false)

        val expr2 = Expr.Equals(
            left = Expr.Get<Any>(Expr.Constant("nullValue")),
            right = Expr.Get<Any>(Expr.Constant("nonexistent"))
        )
        assertTrue(expr2.evaluate(properties, 0.0) == true)
    }

    @Test
    fun testToBooleanExpression() {
        val nullExpr = Expr.ToBoolean(Expr.Constant(null))
        assertTrue(nullExpr.evaluate(null, null) == false)

        val emptyStringExpr = Expr.ToBoolean(Expr.Constant(""))
        assertTrue(emptyStringExpr.evaluate(null, null) == false)

        val nonEmptyStringExpr = Expr.ToBoolean(Expr.Constant("test"))
        assertTrue(nonEmptyStringExpr.evaluate(null, null) == true)

        val zeroExpr = Expr.ToBoolean(Expr.Constant(0))
        assertTrue(zeroExpr.evaluate(null, null) == false)

        val nonZeroExpr = Expr.ToBoolean(Expr.Constant(42))
        assertTrue(nonZeroExpr.evaluate(null, null) == true)

        val nanExpr = Expr.ToBoolean(Expr.Constant(Double.NaN))
        assertTrue(nanExpr.evaluate(null, null) == false)

        val trueExpr = Expr.ToBoolean(Expr.Constant(true))
        assertTrue(trueExpr.evaluate(null, null) == true)

        val falseExpr = Expr.ToBoolean(Expr.Constant(false))
        assertTrue(falseExpr.evaluate(null, null) == false)

        val objectExpr = Expr.ToBoolean(Expr.Constant(mapOf("key" to "value")))
        assertTrue(objectExpr.evaluate(null, null) == true)
    }

    @Test
    fun testToStringExpression() {
        val nullExpr = Expr.ToString(Expr.Constant<Any?>(null))
        assertEquals("", nullExpr.evaluate(null, null))

        val emptyStringExpr = Expr.ToString(Expr.Constant(""))
        assertEquals("", emptyStringExpr.evaluate(null, null))

        val nonEmptyStringExpr = Expr.ToString(Expr.Constant("test"))
        assertEquals("test", nonEmptyStringExpr.evaluate(null, null))

        val trueExpr = Expr.ToString(Expr.Constant(true))
        assertEquals("true", trueExpr.evaluate(null, null))

        val falseExpr = Expr.ToString(Expr.Constant(false))
        assertEquals("false", falseExpr.evaluate(null, null))

        val numberExpr = Expr.ToString(Expr.Constant(42))
        assertEquals("42", numberExpr.evaluate(null, null))

        val doubleExpr = Expr.ToString(Expr.Constant(3.14))
        assertEquals("3.14", doubleExpr.evaluate(null, null))

        val colorExpr = Expr.ToString(Expr.Constant(Color(255, 0, 0, 128)))
        assertEquals("rgba(255,0,0,0.5)", colorExpr.evaluate(null, null))

        val objectExpr = Expr.ToString(Expr.Constant(mapOf("key" to "value")))
        assertEquals("""{"key":"value"}""", objectExpr.evaluate(null, null))

        val arrayExpr = Expr.ToString(Expr.Constant(listOf(1, 2, 3)))
        assertEquals("[1,2,3]", arrayExpr.evaluate(null, null))
    }

    @Test
    fun `isExpression returns true for array with string first element`() {
        val input = JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name")))
        assertTrue(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `isExpression returns false for empty array`() {
        val input = JsonArray(emptyList())
        assertFalse(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `isExpression returns false for array with non-string first element`() {
        val input = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("name")))
        assertFalse(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `isExpression returns true for object with stops property`() {
        val input = JsonObject(mapOf("stops" to JsonArray(emptyList())))
        assertTrue(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `isExpression returns false for object without stops property`() {
        val input = JsonObject(mapOf("color" to JsonPrimitive("red")))
        assertFalse(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `isExpression returns false for primitive value`() {
        val input = JsonPrimitive("test")
        assertFalse(ExpressionOrValue.isExpression(input))
    }

    @Test
    fun `test case expression with equals condition`() {
        val jsonStr = """
        [
          "case",
          [
            "==",
            [
              "get",
              "brunnel"
            ],
            "tunnel"
          ],
          0.7,
          1
        ]
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val exprOrValue = json.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), jsonStr)
        
        assertTrue(exprOrValue is ExpressionOrValue.Expression<Double>)
        val expr = (exprOrValue as ExpressionOrValue.Expression<Double>).expr
        
        assertTrue(expr is Expr.Case<Double>)
        val caseExpr = expr as Expr.Case<Double>
        
        assertEquals(1, caseExpr.conditions.size)
        
        val (condition, result) = caseExpr.conditions[0]
        assertTrue(condition is Expr.Equals<*, *>)
        val equalsExpr = condition
        
        assertTrue(equalsExpr.left is Expr.Get<*>)
        val getExpr = equalsExpr.left
        assertEquals("brunnel", (getExpr.property as Expr.Constant).value)
        
        assertTrue(equalsExpr.right is Expr.Constant<*>)
        val constantExpr = equalsExpr.right
        assertEquals("tunnel", constantExpr.value)
        
        assertTrue(result is Expr.Constant<Double>)
        val resultExpr = result
        assertEquals(0.7, resultExpr.value)
        
        assertTrue(caseExpr.default is Expr.Constant<Double>)
        val defaultExpr = caseExpr.default
        assertEquals(1.0, defaultExpr.value)
        
        val tunnelProps = mapOf("brunnel" to "tunnel")
        val nonTunnelProps = mapOf("brunnel" to "bridge")
        
        assertEquals(0.7, exprOrValue.process(featureProperties = tunnelProps))
        assertEquals(1.0, exprOrValue.process(featureProperties = nonTunnelProps))
    }
} 