package ovh.plrapps.mapcompose.maplibre.spec.style.props

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.maplibre.data.json
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ColorSerializer
import ovh.plrapps.mapcompose.maplibre.spec.style.serializers.ExpressionOrValueSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ExpressionOrValueColorInterpolateTest {
    @Test
    fun `deserialize interpolate with case`() {
        val jsonStr = """["interpolate",["linear",1],["zoom"],3,0.75,4,0.8,11,["case",["<=",["get","admin_level"],6],1.75,1.5],18,["case",["<=",["get","admin_level"],6],3,2]]"""
        val exprOrValue = json.decodeFromString(ExpressionOrValueSerializer(Float.serializer()), jsonStr)
        // TODO, need to finish
        assertTrue(exprOrValue is ExpressionOrValue.Expression)
    }

    @Test
    fun `deserialize interpolate with match and colors`() {
        val jsonStr = """
        [
          "interpolate",
          ["linear"],
          ["zoom"],
          9,
          [
            "match",
            ["get", "class"],
            ["industrial"], "hsl(40,67%,90%)",
            "quarry", "hsla(32, 47%, 87%, 0.2)",
            "hsl(60, 31%, 87%)"
          ],
          16,
          [
            "match",
            ["get", "class"],
            ["industrial"], "hsl(49,54%,90%)",
            "quarry", "hsla(32, 47%, 87%, 0.5)",
            "hsl(60, 31%, 87%)"
          ]
        ]
        """.trimIndent()

        val exprOrValue = json.decodeFromString(ExpressionOrValueSerializer(ColorSerializer), jsonStr)
        exprOrValue.process()
        assertTrue(exprOrValue is ExpressionOrValue.Expression)
        val expr = (exprOrValue as ExpressionOrValue.Expression<Color>).expr
        assertTrue(expr is Expr.Interpolate)
        val interpolate = expr as Expr.Interpolate<Color>
        assertEquals(2, interpolate.stops.size)
        interpolate.stops.forEach { (_, value) ->
            assertTrue(value is Expr.Match<*> || value is Expr.Constant<*>)
        }
    }

    @Test
    fun `deserialize step expression`() {
        val jsonStr = """["step",["zoom"],1,9,["match",["get","class"],"quarry",0,1],10,1]"""
        val exprOrValue = json.decodeFromString(ExpressionOrValueSerializer(Float.serializer()), jsonStr)
        
        assertTrue(exprOrValue is ExpressionOrValue.Expression)
        val expr = (exprOrValue as ExpressionOrValue.Expression<*>).expr
        assertTrue(expr is Expr.Step)
        
        val step = expr as Expr.Step<*>
        assertEquals(Expr.Zoom, step.input)
        assertEquals(Expr.Constant(1.0), step.default)
        assertEquals(2, step.stops.size)
        
        val (stop1, value1) = step.stops[0]
        assertEquals(9.0, stop1)
        assertTrue(value1 is Expr.Match<*>)
        
        val (stop2, value2) = step.stops[1]
        assertEquals(10.0, stop2)
        assertTrue(value2 is Expr.Constant<*>)
        assertEquals(1.0, (value2 as Expr.Constant<*>).value)
    }

    @Test
    fun `deserialize interpolate and process`() {
        val jsonStr = """{
                    "stops": [
                        [
                            2,
                            "{ABBREV}"
                        ],
                        [
                            4,
                            "{NAME}"
                        ]
                    ]
                }"""
        val featureProperties = mapOf("NAME" to "United Kingdom", "ABBREV" to "U.K.")
        val textField = json.decodeFromString(ExpressionOrValueSerializer(String.serializer()), jsonStr)
        assertEquals("{ABBREV}", textField.process(featureProperties, zoom = 2.0))
        assertEquals("{ABBREV}", textField.process(featureProperties, zoom = 3.0))
        assertEquals("{NAME}", textField.process(featureProperties, zoom = 4.0))
        assertEquals("{NAME}", textField.process(featureProperties, zoom = 5.0))
    }
} 