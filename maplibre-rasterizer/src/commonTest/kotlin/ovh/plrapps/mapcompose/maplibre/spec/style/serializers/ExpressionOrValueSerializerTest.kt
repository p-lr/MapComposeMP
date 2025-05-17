package ovh.plrapps.mapcompose.maplibre.spec.style.serializers

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.data.json
import ovh.plrapps.mapcompose.maplibre.spec.style.props.Expr
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ExpressionOrValueSerializerTest {
    @Test
    fun `test serialize simple int value`() {
        val value = ExpressionOrValue.Value(42)
        val serialized = json.encodeToString(ExpressionOrValueSerializer(Int.serializer()), value)
        assertEquals("42", serialized)
    }

    @Test
    fun `test serialize color value`() {
        val value = ExpressionOrValue.Value(Color(0xFF0000FF))
        val serialized = json.encodeToString(ExpressionOrValueSerializer(ColorSerializer), value)
        assertEquals("#0000FF", serialized)
    }

    @Test
    fun `test deserialize simple int value`() {
        val jsonStr = "42"
        val deserialized = json.decodeFromString(
            ExpressionOrValueSerializer(Int.serializer()),
            jsonStr
        )
        assertTrue(deserialized is ExpressionOrValue.Value)
        assertEquals(42, deserialized.value)
    }

    @Test
    fun `test deserialize color value`() {
        val jsonStr = "#0000FF"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(ColorSerializer), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Value)
        assertEquals(Color(0xFF0000FF), deserialized.value)
    }

    @Test
    fun `test deserialize get expression`() {
        val jsonStr = "[\"get\",\"name\"]"
        val deserialized = json.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            jsonStr
        )
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Get<*>)
    }

    @Test
    fun `test deserialize interpolate expression`() {
        val jsonStr = "[\"interpolate\",[\"linear\"],[\"zoom\"],0,0,10,100]"
        val deserialized = json.decodeFromString(
            ExpressionOrValueSerializer(Double.serializer()),
            jsonStr
        )
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Interpolate<*>)
    }

    @Test
    fun `test deserialize match expression`() {
        val jsonStr = "[\"match\",[\"get\",\"type\"],\"residential\",\"local\",\"other\"]"
        val deserialized =
            json.decodeFromString<ExpressionOrValue<String>>(ExpressionOrValueSerializer(String.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Match<*>)
    }

    @Test
    fun `test deserialize stops expression`() {
        val jsonStr = "{\"stops\":[[0,2],[6,6],[14,9],[22,18]]}"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Interpolate)

        val stops = (deserialized.expr).stops
        assertEquals(4, stops.size)
        assertEquals(0.0 to Expr.Constant(2.0), stops[0])
        assertEquals(6.0 to Expr.Constant(6.0), stops[1])
        assertEquals(14.0 to Expr.Constant(9.0), stops[2])
        assertEquals(22.0 to Expr.Constant(18.0), stops[3])
    }

    @Test
    fun `test deserialize has expression`() {
        val jsonStr = "[\"has\",\"name\"]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Has<*>)
        assertEquals("name", (deserialized.expr as Expr.Has<*>).property)
    }

    @Test
    fun `test deserialize in expression`() {
        val jsonStr = "[\"in\",[\"get\",\"type\"],[\"residential\",\"commercial\"]]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.In<*>)
        val inExpr = deserialized.expr as Expr.In<*>
        assertTrue(inExpr.value is Expr.Get)
        assertEquals(listOf("residential", "commercial"), inExpr.array)
    }

    @Test
    fun `test deserialize case expression`() {
        val jsonStr = "[\"case\",[\"has\",\"name\"],\"named\",\"unnamed\"]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(String.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Case)
        val caseExpr = deserialized.expr as Expr.Case<String>
        assertEquals(1, caseExpr.conditions.size)
        assertTrue(caseExpr.conditions[0].first is Expr.Has<*>)
        assertTrue(caseExpr.conditions[0].second is Expr.Constant)
        assertTrue(caseExpr.default is Expr.Constant)
    }

    @Test
    fun `test deserialize coalesce expression`() {
        val jsonStr = "[\"coalesce\",[\"get\",\"name\"],[\"get\",\"title\"],\"unnamed\"]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(String.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Coalesce)
        val coalesceExpr = deserialized.expr as Expr.Coalesce<String>
        assertEquals(3, coalesceExpr.values.size)
        assertTrue(coalesceExpr.values[0] is Expr.Get)
        assertTrue(coalesceExpr.values[1] is Expr.Get)
        assertTrue(coalesceExpr.values[2] is Expr.Constant)
    }

    @Test
    fun `test deserialize invalid json`() {
        try {
            json.decodeFromString<ExpressionOrValue<Int>>(
                ExpressionOrValueSerializer(Int.serializer()),
                "invalid"
            )
            fail("Expected exception was not thrown")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun testNotEqualsExpressionDeserialization() {
        val json = """["!=",["get","property1"],"value"]"""
        val expr = Json.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            json
        ) as ExpressionOrValue.Expression<Boolean>

        val result = expr.expr.evaluate(mapOf("property1" to "different"), 0.0)
        assertTrue(result == true)

        val result2 = expr.expr.evaluate(mapOf("property1" to "value"), 0.0)
        assertTrue(result2 == false)
    }

    @Test
    fun testNotEqualsExpressionWithConstantsDeserialization() {
        val json = """["!=","value1","value2"]"""
        val expr = Json.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            json
        ) as ExpressionOrValue.Expression<Boolean>

        val result = expr.expr.evaluate(emptyMap(), 0.0)
        assertTrue(result == true)
    }

    @Test
    fun testEqualsExpressionDeserialization() {
        val json = """["==",["get","property1"],"value"]"""
        val expr = Json.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            json
        ) as ExpressionOrValue.Expression<Boolean>

        val result = expr.expr.evaluate(mapOf("property1" to "different"), 0.0)
        assertTrue(result == false)

        val result2 = expr.expr.evaluate(mapOf("property1" to "value"), 0.0)
        assertTrue(result2 == true)
    }

    @Test
    fun testEqualsExpressionWithConstantsDeserialization() {
        val json = """["==","value1","value2"]"""
        val expr = Json.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            json
        ) as ExpressionOrValue.Expression<Boolean>

        val result = expr.expr.evaluate(emptyMap(), 0.0)
        assertTrue(result == false)
    }


    @Test
    fun `test deserialize equals expression`() {
        val jsonStr = "[\"==\",[\"get\",\"name\"],\"test\"]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Equals<*, *>)
    }

    @Test
    fun `test equals expression with constants`() {
        val expr = Expr.Equals(
            Expr.Constant<String>("test"),
            Expr.Constant<String>("test")
        )
        assertEquals(true, expr.evaluate(null, null))
    }

    @Test
    fun `test deserialize less than expression`() {
        val jsonStr = "[\"<\",[\"get\",\"value\"],10]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.LessThan<*>)
    }

    @Test
    fun `test deserialize less than or equal expression`() {
        val jsonStr = "[\"<=\",[\"get\",\"value\"],10]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.LessThanOrEqual<*>)
    }

    @Test
    fun `test deserialize greater than expression`() {
        val jsonStr = "[\">\",[\"get\",\"value\"],10]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.GreaterThan<*>)
    }

    @Test
    fun `test deserialize greater than or equal expression`() {
        val jsonStr = "[\">=\",[\"get\",\"value\"],10]"
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.GreaterThanOrEqual<*>)
    }

    @Test
    fun `test evaluate less than expression`() {
        val expr = Expr.LessThan<Boolean>(
            Expr.Get<Int>(Expr.Constant("value")),
            Expr.Constant(10)
        )

        assertEquals(true, expr.evaluate(mapOf("value" to 5), null))

        assertEquals(false, expr.evaluate(mapOf("value" to 10), null))

        assertEquals(false, expr.evaluate(mapOf("value" to 15), null))

        assertEquals(null, expr.evaluate(emptyMap(), null))
    }

    @Test
    fun `test evaluate less than or equal expression`() {
        val expr = Expr.LessThanOrEqual<Boolean>(
            Expr.Get<Int>(Expr.Constant("value")),
            Expr.Constant(10)
        )

        assertEquals(true, expr.evaluate(mapOf("value" to 5), null))

        assertEquals(true, expr.evaluate(mapOf("value" to 10), null))

        assertEquals(false, expr.evaluate(mapOf("value" to 15), null))

        assertEquals(null, expr.evaluate(emptyMap(), null))
    }

    @Test
    fun `test evaluate greater than expression`() {
        val expr = Expr.GreaterThan<Boolean>(
            Expr.Get<Int>(Expr.Constant("value")),
            Expr.Constant(10)
        )

        assertEquals(false, expr.evaluate(mapOf("value" to 5), null))

        assertEquals(false, expr.evaluate(mapOf("value" to 10), null))

        assertEquals(true, expr.evaluate(mapOf("value" to 15), null))

        assertEquals(null, expr.evaluate(emptyMap(), null))
    }

    @Test
    fun `test evaluate greater than or equal expression`() {
        val expr = Expr.GreaterThanOrEqual<Boolean>(
            Expr.Get<Int>(Expr.Constant("value")),
            Expr.Constant(10)
        )

        assertEquals(false, expr.evaluate(mapOf("value" to 5), null))

        assertEquals(true, expr.evaluate(mapOf("value" to 10), null))

        assertEquals(true, expr.evaluate(mapOf("value" to 15), null))

        assertEquals(null, expr.evaluate(emptyMap(), null))
    }

    @Test
    fun `test evaluate string comparison`() {
        val expr = Expr.LessThan<Boolean>(
            Expr.Get<String>(Expr.Constant("value")),
            Expr.Constant("z")
        )

        assertEquals(true, expr.evaluate(mapOf("value" to "a"), null))
        assertEquals(false, expr.evaluate(mapOf("value" to "z"), null))
        assertEquals(false, expr.evaluate(mapOf("value" to "zz"), null))
    }

    @Test
    fun `test evaluate mixed type comparison`() {
        val expr = Expr.LessThan<Boolean>(
            Expr.Get<Any>(Expr.Constant("value")),
            Expr.Constant(10)
        )

        assertEquals(null, expr.evaluate(mapOf("value" to "string"), null))
        assertEquals(true, expr.evaluate(mapOf("value" to 5), null))
        assertEquals(false, expr.evaluate(mapOf("value" to 15), null))
    }

    @Test
    fun `test deserialize all expression`() {
        val jsonStr = """["all",["get","prop1"],["get","prop2"]]"""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.All)
        assertEquals(2, (deserialized.expr as Expr.All).conditions.size)
    }

    @Test
    fun `test deserialize any expression`() {
        val jsonStr = """["any",["get","prop1"],["get","prop2"]]"""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.AnyOf)
        assertEquals(2, (deserialized.expr as Expr.AnyOf).conditions.size)
    }

    @Test
    fun `test deserialize not expression`() {
        val jsonStr = """["!",["get","prop1"]]"""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Boolean.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Not)
    }

    @Test
    fun `test evaluate all expression`() {
        val expr = Expr.All(
            listOf(
                Expr.Get<Boolean>(Expr.Constant("prop1")),
                Expr.Get<Boolean>(Expr.Constant("prop2"))
            )
        )

        assertEquals(true, expr.evaluate(mapOf("prop1" to true, "prop2" to true), null))

        assertEquals(false, expr.evaluate(mapOf("prop1" to true, "prop2" to false), null))

        assertEquals(null, expr.evaluate(mapOf("prop1" to true), null))

        assertEquals(null, Expr.All(emptyList()).evaluate(null, null))
    }

    @Test
    fun `test evaluate any expression`() {
        val expr = Expr.AnyOf(
            listOf(
                Expr.Get<Boolean>(Expr.Constant("prop1")),
                Expr.Get<Boolean>(Expr.Constant("prop2"))
            )
        )

        assertEquals(true, expr.evaluate(mapOf("prop1" to true, "prop2" to false), null))

        assertEquals(false, expr.evaluate(mapOf("prop1" to false, "prop2" to false), null))

        assertEquals(null, expr.evaluate(mapOf("prop1" to false), null))

        assertEquals(null, Expr.AnyOf(emptyList()).evaluate(null, null))
    }

    @Test
    fun `test evaluate not expression`() {
        val expr = Expr.Not(Expr.Get<Boolean>(Expr.Constant("prop1")))

        assertEquals(false, expr.evaluate(mapOf("prop1" to true), null))

        assertEquals(true, expr.evaluate(mapOf("prop1" to false), null))

        assertEquals(null, expr.evaluate(emptyMap(), null))
    }

    @Test
    fun `test complex logical expressions`() {
        val expr = Expr.All(
            listOf(
                Expr.Not(Expr.Get<Boolean>(Expr.Constant("prop1"))),
                Expr.AnyOf(
                    listOf(
                        Expr.Get<Boolean>(Expr.Constant("prop2")),
                        Expr.Get<Boolean>(Expr.Constant("prop3"))
                    )
                )
            )
        )

        // prop1=false, prop2=true, prop3=false
        assertEquals(
            true, expr.evaluate(
                mapOf(
                    "prop1" to false,
                    "prop2" to true,
                    "prop3" to false
                ), null
            )
        )

        // prop1=true, prop2=false, prop3=false
        assertEquals(
            false, expr.evaluate(
                mapOf(
                    "prop1" to true,
                    "prop2" to false,
                    "prop3" to false
                ), null
            )
        )

        // prop1=false, prop2=false, prop3=false
        assertEquals(
            false, expr.evaluate(
                mapOf(
                    "prop1" to false,
                    "prop2" to false,
                    "prop3" to false
                ), null
            )
        )
    }

    @Test
    fun `test deserialize modulo expression`() {
        val jsonStr = """["%",["get","value"],3]"""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        assertTrue(deserialized.expr is Expr.Modulo)
    }

    @Test
    fun `test evaluate modulo expression`() {
        val expr = Expr.Modulo(Expr.Get<Int>(Expr.Constant("value")), Expr.Constant(3))
        val exprWithZeroDivisor = Expr.Modulo(Expr.Constant(7), Expr.Constant(0))

        assertEquals(1.0, expr.evaluate(mapOf("value" to 7), null))
        assertEquals(null, exprWithZeroDivisor.evaluate(emptyMap(), null))
        assertEquals(null, expr.evaluate(mapOf("value" to null), null))
        assertEquals(null, expr.evaluate(mapOf("value" to "not a number"), null))
    }

    @Test
    fun `test evaluate modulo with different number types`() {
        val expr = Expr.Modulo(
            Expr.Get<Any>(Expr.Constant("value")),
            Expr.Constant(3)
        )

        // Int
        assertEquals(1.0, expr.evaluate(mapOf("value" to 7), null))

        // Double
        assertEquals(1.0, expr.evaluate(mapOf("value" to 7.0), null))

        // Float
        assertEquals(1.0, expr.evaluate(mapOf("value" to 7.0f), null))

        // Long
        assertEquals(1.0, expr.evaluate(mapOf("value" to 7L), null))
    }

    @Test
    fun `test complex modulo expression`() {
        val expr = Expr.Modulo(
            Expr.Get<Int>(Expr.Constant("value")),
            Expr.Modulo(
                Expr.Get<Int>(Expr.Constant("divisor")),
                Expr.Constant(2)
            )
        )

        // value = 7, divisor = 5
        // 7 % (5 % 2) = 7 % 1 = 0
        assertEquals(
            0.0, expr.evaluate(
                mapOf(
                    "value" to 7,
                    "divisor" to 5
                ), null
            )
        )

        // value = 7, divisor = 4
        // 7 % (4 % 2) = 7 % 0 = null (div 0)
        assertEquals(
            null, expr.evaluate(
                mapOf(
                    "value" to 7,
                    "divisor" to 4
                ), null
            )
        )
    }

    @Test
    fun `test evaluate power expression`() {
        val expr = Expr.Power(Expr.Get<Double>(Expr.Constant("value")), Expr.Constant(2.0))
        assertEquals(4.0, expr.evaluate(mapOf("value" to 2.0), 0.0))
        assertEquals(9.0, expr.evaluate(mapOf("value" to 3.0), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to "not a number"), 0.0))
    }

    @Test
    fun `test evaluate abs expression`() {
        val expr = Expr.Abs(Expr.Get<Double>(Expr.Constant("value")))
        assertEquals(2.0, expr.evaluate(mapOf("value" to 2.0), 0.0))
        assertEquals(2.0, expr.evaluate(mapOf("value" to -2.0), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to "not a number"), 0.0))
    }

    @Test
    fun `test evaluate trigonometric expressions`() {
        val sinExpr = Expr.Sin(Expr.Get<Double>(Expr.Constant("value")))
        val cosExpr = Expr.Cos(Expr.Get<Double>(Expr.Constant("value")))
        val tanExpr = Expr.Tan(Expr.Get<Double>(Expr.Constant("value")))
        val asinExpr = Expr.Asin(Expr.Get<Double>(Expr.Constant("value")))
        val acosExpr = Expr.Acos(Expr.Get<Double>(Expr.Constant("value")))
        val atanExpr = Expr.Atan(Expr.Get<Double>(Expr.Constant("value")))

        assertEquals(0.0, sinExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(1.0, cosExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(0.0, tanExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(0.0, asinExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(PI / 2, acosExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(0.0, atanExpr.evaluate(mapOf("value" to 0.0), 0.0))

        assertEquals(null, sinExpr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, sinExpr.evaluate(mapOf("value" to "not a number"), 0.0))
    }

    @Test
    fun `test evaluate rounding expressions`() {
        val ceilExpr = Expr.Ceil(Expr.Get<Double>(Expr.Constant("value")))
        val floorExpr = Expr.Floor(Expr.Get<Double>(Expr.Constant("value")))
        val roundExpr = Expr.Round(Expr.Get<Double>(Expr.Constant("value")))

        assertEquals(3.0, ceilExpr.evaluate(mapOf("value" to 2.3), 0.0))
        assertEquals(2.0, floorExpr.evaluate(mapOf("value" to 2.3), 0.0))
        assertEquals(2.0, roundExpr.evaluate(mapOf("value" to 2.3), 0.0))

        assertEquals(null, ceilExpr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, ceilExpr.evaluate(mapOf("value" to "not a number"), 0.0))
    }

    @Test
    fun `test evaluate logarithmic expressions`() {
        val lnExpr = Expr.Ln(Expr.Get<Double>(Expr.Constant("value")))
        val log10Expr = Expr.Log10(Expr.Get<Double>(Expr.Constant("value")))

        assertEquals(0.0, lnExpr.evaluate(mapOf("value" to 1.0), 0.0))
        assertEquals(0.0, log10Expr.evaluate(mapOf("value" to 1.0), 0.0))

        assertEquals(null, lnExpr.evaluate(mapOf("value" to 0.0), 0.0))
        assertEquals(null, lnExpr.evaluate(mapOf("value" to -1.0), 0.0))
        assertEquals(null, lnExpr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, lnExpr.evaluate(mapOf("value" to "not a number"), 0.0))
    }

    @Test
    fun `test evaluate min max expressions`() {
        val minExpr = Expr.Min(Expr.Get<Double>(Expr.Constant("value1")), Expr.Get<Double>(Expr.Constant("value2")))
        val maxExpr = Expr.Max(Expr.Get<Double>(Expr.Constant("value1")), Expr.Get<Double>(Expr.Constant("value2")))

        assertEquals(2.0, minExpr.evaluate(mapOf("value1" to 2.0, "value2" to 3.0), 0.0))
        assertEquals(3.0, maxExpr.evaluate(mapOf("value1" to 2.0, "value2" to 3.0), 0.0))

        assertEquals(null, minExpr.evaluate(mapOf("value1" to null, "value2" to 3.0), 0.0))
        assertEquals(null, minExpr.evaluate(mapOf("value1" to "not a number", "value2" to 3.0), 0.0))
    }

    @Test
    fun `test evaluate sqrt expression`() {
        val expr = Expr.Sqrt(Expr.Get<Double>(Expr.Constant("value")))
        assertEquals(2.0, expr.evaluate(mapOf("value" to 4.0), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to -1.0), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to null), 0.0))
        assertEquals(null, expr.evaluate(mapOf("value" to "not a number"), 0.0))
    }



    @Test
    fun `test deserialize hsl color value`() {
        val jsonStr = """"hsl(75, 51%, 85%)""""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(ColorSerializer), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Value)
        assertEquals(Color.hsl(hue = 75F, saturation = 0.51F, lightness = 0.85F), deserialized.value)
    }

    @Test
    fun `test deserialize expression color value`() {
        val jsonStr = """[
                    "match",
                    [
                        "get",
                        "ADM0_A3"
                    ],
                    [
                        "ARM",
                        "ATG",
                        "AUS",
                        "BTN",
                        "CAN",
                        "COG",
                        "CZE",
                        "GHA",
                        "GIN",
                        "HTI",
                        "ISL",
                        "JOR",
                        "KHM",
                        "KOR",
                        "LVA",
                        "MLT",
                        "MNE",
                        "MOZ",
                        "PER",
                        "SAH",
                        "SGP",
                        "SLV",
                        "SOM",
                        "TJK",
                        "TUV",
                        "UKR",
                        "WSM"
                    ],
                    "#D6C7FF",
                    [
                        "AZE",
                        "BGD",
                        "CHL",
                        "CMR",
                        "CSI",
                        "DEU",
                        "DJI",
                        "GUY",
                        "HUN",
                        "IOA",
                        "JAM",
                        "LBN",
                        "LBY",
                        "LSO",
                        "MDG",
                        "MKD",
                        "MNG",
                        "MRT",
                        "NIU",
                        "NZL",
                        "PCN",
                        "PYF",
                        "SAU",
                        "SHN",
                        "STP",
                        "TTO",
                        "UGA",
                        "UZB",
                        "ZMB"
                    ],
                    "#EBCA8A",
                    [
                        "AGO",
                        "ASM",
                        "ATF",
                        "BDI",
                        "BFA",
                        "BGR",
                        "BLZ",
                        "BRA",
                        "CHN",
                        "CRI",
                        "ESP",
                        "HKG",
                        "HRV",
                        "IDN",
                        "IRN",
                        "ISR",
                        "KNA",
                        "LBR",
                        "LCA",
                        "MAC",
                        "MUS",
                        "NOR",
                        "PLW",
                        "POL",
                        "PRI",
                        "SDN",
                        "TUN",
                        "UMI",
                        "USA",
                        "USG",
                        "VIR",
                        "VUT"
                    ],
                    "#C1E599",
                    [
                        "ARE",
                        "ARG",
                        "BHS",
                        "CIV",
                        "CLP",
                        "DMA",
                        "ETH",
                        "GAB",
                        "GRD",
                        "HMD",
                        "IND",
                        "IOT",
                        "IRL",
                        "IRQ",
                        "ITA",
                        "KOS",
                        "LUX",
                        "MEX",
                        "NAM",
                        "NER",
                        "PHL",
                        "PRT",
                        "RUS",
                        "SEN",
                        "SUR",
                        "TZA",
                        "VAT"
                    ],
                    "#E7E58F",
                    [
                        "AUT",
                        "BEL",
                        "BHR",
                        "BMU",
                        "BRB",
                        "CYN",
                        "DZA",
                        "EST",
                        "FLK",
                        "GMB",
                        "GUM",
                        "HND",
                        "JEY",
                        "KGZ",
                        "LIE",
                        "MAF",
                        "MDA",
                        "NGA",
                        "NRU",
                        "SLB",
                        "SOL",
                        "SRB",
                        "SWZ",
                        "THA",
                        "TUR",
                        "VEN",
                        "VGB"
                    ],
                    "#98DDA1",
                    [
                        "AIA",
                        "BIH",
                        "BLM",
                        "BRN",
                        "CAF",
                        "CHE",
                        "COM",
                        "CPV",
                        "CUB",
                        "ECU",
                        "ESB",
                        "FSM",
                        "GAZ",
                        "GBR",
                        "GEO",
                        "KEN",
                        "LTU",
                        "MAR",
                        "MCO",
                        "MDV",
                        "NFK",
                        "NPL",
                        "PNG",
                        "PRY",
                        "QAT",
                        "SLE",
                        "SPM",
                        "SYC",
                        "TCA",
                        "TKM",
                        "TLS",
                        "VNM",
                        "WEB",
                        "WSB",
                        "YEM",
                        "ZWE"
                    ],
                    "#83D5F4",
                    [
                        "ABW",
                        "ALB",
                        "AND",
                        "ATC",
                        "BOL",
                        "COD",
                        "CUW",
                        "CYM",
                        "CYP",
                        "EGY",
                        "FJI",
                        "GGY",
                        "IMN",
                        "KAB",
                        "KAZ",
                        "KWT",
                        "LAO",
                        "MLI",
                        "MNP",
                        "MSR",
                        "MYS",
                        "NIC",
                        "NLD",
                        "PAK",
                        "PAN",
                        "PRK",
                        "ROU",
                        "SGS",
                        "SVN",
                        "SWE",
                        "TGO",
                        "TWN",
                        "VCT",
                        "ZAF"
                    ],
                    "#B1BBF9",
                    [
                        "ATA",
                        "GRL"
                    ],
                    "#FFFFFF",
                    "#EAB38F"
                ]"""
        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(ColorSerializer), jsonStr)
        assertTrue(deserialized is ExpressionOrValue.Expression)
        deserialized.expr as Expr.Match
        val firstBranch = deserialized.expr.branches.first()
        val matchExpr = deserialized.expr
        assertEquals(Color(0xFFD6C7FF), (firstBranch.second as Expr.Constant<Color>).value)
        assertEquals(Color(0xFFEAB38F), (matchExpr.elseExpr as Expr.Constant<Color>).value)
    }

    @Test
    fun `test deserialize legacy expression 001`() {
        val input = """{
                    "stops": [
                        [
                            0,
                            2
                        ],
                        [
                            6,
                            6
                        ],
                        [
                            14,
                            9
                        ],
                        [
                            22,
                            18
                        ]
                    ]
                }"""

        val deserialized = json.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), input)

        assertEquals(6.0, deserialized.process(null, zoom = 6.0))

    }

    @Test
    fun toNumberTest() {
        val input = JsonArray(
            listOf(
                JsonPrimitive("to-number"),
                JsonArray(
                    listOf(
                        JsonPrimitive("get"),
                        JsonPrimitive("rank")
                    )
                )
            )
        )

        val expr = json.decodeFromJsonElement(ExpressionOrValueSerializer(Double.serializer()), input)


        val result = expr.process(mapOf("rank" to "123"), zoom = null)
        assertEquals(123.0, result)
    }
} 