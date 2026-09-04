package ovh.plrapps.mapcompose.vector.spec.style.props

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The public façade over the expression engine: constants, expressions and the typed `processAs*`
 * accessors the painters use. Operator semantics are covered exhaustively by
 * `ExpressionConformanceTest`; this file covers the MapCompose-specific wrapper.
 */
class ExpressionOrValueTest {

    private val bare = Json { ignoreUnknownKeys = true }

    private fun feature(vararg properties: Pair<String, Any?>, type: String = "Point") =
        EvalFeature(type = type, properties = properties.toMap())

    @Test
    fun constantValueIsReturnedAsIs() {
        val value = ExpressionOrValue.Value(42.0)
        assertEquals(42.0, value.process())
        assertEquals(42.0, value.processAsDouble())
        assertEquals(42f, value.processAsFloat())
    }

    @Test
    fun getReadsAFeatureProperty() {
        val expr = bare.decodeFromString(ExpressionOrValueSerializer(String.serializer()), """["get","name"]""")
        assertTrue(expr is ExpressionOrValue.Expression)
        assertEquals("test", expr.processAsString(feature("name" to "test")))
        // String-valued properties are *coerced* at the top level rather than asserted, so a
        // missing property yields "" — this is MapLibre's behaviour, see createExpression.
        assertEquals("", expr.processAsString(feature()))
    }

    @Test
    fun zoomDrivenStepSelectsTheRightBranch() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            """["step",["zoom"],"small",10,"medium",15,"large"]""",
        )
        assertEquals("small", expr.processAsString(zoom = 8.0))
        assertEquals("medium", expr.processAsString(zoom = 10.0))
        assertEquals("medium", expr.processAsString(zoom = 14.9))
        assertEquals("large", expr.processAsString(zoom = 15.0))
    }

    @Test
    fun interpolateOverNumberArraysCompiles() {
        /* `text-offset` is `array<number, 2>` in the style spec, but the expected type derived here
         * from the property's Kotlin serializer (`List<Double>`) carries no length, and upstream's
         * own check rejects a length-less array as not interpolatable. `Interpolate` therefore takes
         * the output type from its stops -- a literal pair types as `array<number, 2>`. Without
         * that, every zoom-driven `text-offset` in a real style failed to compile and the property
         * silently fell back to its spec default of `[0, 0]`. */
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(ListSerializer(Double.serializer())),
            """["interpolate",["exponential",0.8],["zoom"],13,["literal",[0,1.1]],18,["literal",[0,1.7]]]""",
        )
        assertTrue(expr is ExpressionOrValue.Expression)
        // Below the first stop the value is clamped, as it is for a scalar.
        assertEquals(listOf(0.0, 1.1), expr.processAsDoubleList(zoom = 12.0))
        assertEquals(listOf(0.0, 1.7), expr.processAsDoubleList(zoom = 18.0))
        val middle = expr.processAsDoubleList(zoom = 15.5)!!
        assertEquals(0.0, middle[0])
        assertTrue(middle[1] > 1.1 && middle[1] < 1.7, "expected an interpolated offset, got $middle")
    }

    @Test
    fun interpolateOverAnUninterpolatableTypeStillFails() {
        // The length-less-array allowance must not turn the type check off altogether.
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(ListSerializer(String.serializer())),
            """["interpolate",["linear"],["zoom"],0,["literal",["a"]],1,["literal",["b"]]]""",
        )
        assertTrue(expr is ExpressionOrValue.Invalid)
    }

    @Test
    fun matchFallsBackToItsDefault() {
        val expr = json.decodeFromString<ExpressionOrValue<String>>(
            """["match",["get","class"],["school","university"],["get","class"],["case",["has","class"],"","dot"]]"""
        )
        assertEquals("school", expr.processAsString(feature("class" to "school")))
        assertEquals("", expr.processAsString(feature("class" to "park")))
        assertEquals("dot", expr.processAsString(feature()))
    }

    /**
     * MVT decodes numeric properties as Int/Float/Long; the engine normalizes them to Double so a
     * numeric literal in the style still matches. This is the regression for the two engines
     * previously disagreeing on numeric equality.
     */
    @Test
    fun numericPropertiesCompareByValueNotByBoxedType() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            """["==",["get","rank"],1]""",
        )
        assertEquals(true, expr.processAsBoolean(feature("rank" to 1.0)))
        assertEquals(true, expr.processAsBoolean(feature("rank" to 1)))
        assertEquals(true, expr.processAsBoolean(feature("rank" to 1L)))
        assertEquals(false, expr.processAsBoolean(feature("rank" to 2.0)))
    }

    @Test
    fun colorExpressionsEvaluateToColors() {
        val expr = json.decodeFromString<ExpressionOrValue<Color>>(
            """["case",["==",["get","kind"],"water"],"#0000FF","#FF0000"]"""
        )
        assertEquals(Color(0xFF0000FF), expr.processAsColor(feature("kind" to "water")))
        assertEquals(Color(0xFFFF0000), expr.processAsColor(feature("kind" to "land")))
    }

    @Test
    fun listAccessorsCoerceElementTypes() {
        val dashes = bare.decodeFromString(
            ExpressionOrValueSerializer(kotlinx.serialization.builtins.ListSerializer(Double.serializer())),
            """["literal",[2,4]]""",
        )
        assertEquals(listOf(2.0, 4.0), dashes.processAsDoubleList())

        val fonts = bare.decodeFromString(
            ExpressionOrValueSerializer(kotlinx.serialization.builtins.ListSerializer(String.serializer())),
            """["literal",["Roboto","Noto"]]""",
        )
        assertEquals(listOf("Roboto", "Noto"), fonts.processAsStringList())
    }

    @Test
    fun concatBuildsAStringFromProperties() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            """["concat",["get","a"]," - ",["get","b"]]""",
        )
        assertEquals("x - y", expr.processAsString(feature("a" to "x", "b" to "y")))
    }

    @Test
    fun caseAndUpcaseCombine() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            """["upcase",["downcase",["get","name"]]]""",
        )
        assertEquals("HELLO WORLD", expr.processAsString(feature("name" to "Hello World")))
    }

    @Test
    fun indexOfAndSliceOperateOnStrings() {
        val indexOf = bare.decodeFromString(
            ExpressionOrValueSerializer(Double.serializer()),
            """["index-of","c","abcdef"]""",
        )
        assertEquals(2.0, indexOf.processAsDouble())

        val slice = bare.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            """["slice","abcdef",1,3]""",
        )
        assertEquals("bc", slice.processAsString())
    }

    @Test
    fun letAndVarBindIntermediateValues() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(Double.serializer()),
            """["let","r",["get","rank"],["*",["var","r"],2]]""",
        )
        assertEquals(8.0, expr.processAsDouble(feature("rank" to 4.0)))
    }

    @Test
    fun arithmeticOperatorsEvaluate() {
        fun number(source: String) =
            bare.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), source).processAsDouble()

        assertEquals(7.0, number("""["+",3,4]"""))
        assertEquals(-1.0, number("""["-",3,4]"""))
        assertEquals(12.0, number("""["*",3,4]"""))
        assertEquals(0.75, number("""["/",3,4]"""))
        assertEquals(3.0, number("""["%",7,4]"""))
        assertEquals(81.0, number("""["^",3,4]"""))
    }

    @Test
    fun rgbBuildsAColor() {
        val expr = json.decodeFromString<ExpressionOrValue<Color>>("""["rgb",255,0,0]""")
        assertEquals(Color(0xFFFF0000), expr.processAsColor())
    }

    @Test
    fun geometryTypeReadsTheFeatureGeometry() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            """["==",["geometry-type"],"LineString"]""",
        )
        assertEquals(true, expr.processAsBoolean(feature(type = "LineString")))
        assertEquals(false, expr.processAsBoolean(feature(type = "Polygon")))
    }

    @Test
    fun featureIdIsAvailableToExpressions() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(Boolean.serializer()),
            """["==",["id"],7]""",
        )
        assertEquals(true, expr.processAsBoolean(EvalFeature(type = "Point", id = 7.0)))
        assertEquals(false, expr.processAsBoolean(EvalFeature(type = "Point", id = 8.0)))
    }

    @Test
    fun isExpressionRecognisesArraysAndLegacyObjects() {
        assertTrue(ExpressionOrValue.isExpression(json.parseToJsonElement("""["get","x"]""")))
        assertTrue(ExpressionOrValue.isExpression(json.parseToJsonElement("""{"stops":[[0,1]]}""")))
        assertTrue(!ExpressionOrValue.isExpression(json.parseToJsonElement("""["a","b"]""")))
        assertTrue(!ExpressionOrValue.isExpression(json.parseToJsonElement("""5""")))
    }
}
