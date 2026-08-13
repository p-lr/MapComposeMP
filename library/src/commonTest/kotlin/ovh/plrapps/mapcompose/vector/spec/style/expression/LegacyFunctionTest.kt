package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Legacy v7 *function objects* (`{stops}`, `{type}`, `{property}`, `{base}`), which are rewritten
 * into expressions before compilation. Replaces the old `NormalizeLegacyExpressionTest`, which
 * asserted the shape of the rewritten JSON; these assert what the rewritten expression evaluates to,
 * which is what actually matters.
 */
class LegacyFunctionTest {

    private fun feature(vararg properties: Pair<String, Any?>) =
        EvalFeature(type = "Polygon", properties = properties.toMap())

    @Test
    fun zoomStopsOnAnInterpolatablePropertyBecomeAnInterpolate() {
        val expr = json.decodeFromString<ExpressionOrValue<Double>>("""{"stops":[[0,1],[10,11]]}""")
        assertEquals(1.0, expr.processAsDouble(zoom = 0.0))
        assertEquals(6.0, expr.processAsDouble(zoom = 5.0))
        assertEquals(11.0, expr.processAsDouble(zoom = 10.0))
    }

    /**
     * A string property is not interpolatable, so the same shorthand becomes a `step`. Getting this
     * wrong turns `text-field` stops into an "is not interpolatable" parse error.
     */
    @Test
    fun zoomStopsOnAStringPropertyBecomeAStep() {
        val expr = json.decodeFromString<ExpressionOrValue<String>>(
            """{"stops":[[2,"{ABBREV}"],[4,"{NAME}"]]}"""
        )
        assertEquals("{ABBREV}", expr.processAsString(zoom = 2.0))
        assertEquals("{ABBREV}", expr.processAsString(zoom = 3.9))
        assertEquals("{NAME}", expr.processAsString(zoom = 4.0))
    }

    @Test
    fun identityFunctionReadsTheProperty() {
        val expr = json.decodeFromString<ExpressionOrValue<Color>>(
            """{"type":"identity","property":"color"}"""
        )
        assertEquals(Color(0xFFFF0000), expr.processAsColor(feature("color" to "#ff0000")))
    }

    @Test
    fun exponentialFunctionInterpolatesOnTheProperty() {
        val expr = json.decodeFromString<ExpressionOrValue<Double>>(
            """{"type":"exponential","property":"population","base":1,"stops":[[0,0],[100,10]]}"""
        )
        assertEquals(0.0, expr.processAsDouble(feature("population" to 0.0)))
        assertEquals(5.0, expr.processAsDouble(feature("population" to 50.0)))
        assertEquals(10.0, expr.processAsDouble(feature("population" to 100.0)))
    }

    @Test
    fun intervalFunctionBecomesAStepOnTheProperty() {
        val expr = json.decodeFromString<ExpressionOrValue<Double>>(
            """{"type":"interval","property":"rank","stops":[[0,1],[5,2],[10,3]]}"""
        )
        assertEquals(1.0, expr.processAsDouble(feature("rank" to 0.0)))
        assertEquals(1.0, expr.processAsDouble(feature("rank" to 4.9)))
        assertEquals(2.0, expr.processAsDouble(feature("rank" to 5.0)))
        assertEquals(3.0, expr.processAsDouble(feature("rank" to 100.0)))
    }

    @Test
    fun categoricalFunctionBecomesAMatch() {
        val expr = json.decodeFromString<ExpressionOrValue<Color>>(
            """{"type":"categorical","property":"kind","stops":[["a","#ff0000"],["b","#00ff00"]],"default":"#0000ff"}"""
        )
        assertEquals(Color(0xFFFF0000), expr.processAsColor(feature("kind" to "a")))
        assertEquals(Color(0xFF00FF00), expr.processAsColor(feature("kind" to "b")))
        assertEquals(Color(0xFF0000FF), expr.processAsColor(feature("kind" to "z")))
    }

    @Test
    fun categoricalFunctionWithBooleanStopsBecomesACase() {
        val expr = json.decodeFromString<ExpressionOrValue<Double>>(
            """{"type":"categorical","property":"flag","stops":[[true,1],[false,2]],"default":3}"""
        )
        assertEquals(1.0, expr.processAsDouble(feature("flag" to true)))
        assertEquals(2.0, expr.processAsDouble(feature("flag" to false)))
        assertEquals(3.0, expr.processAsDouble(feature()))
    }

    /** A zoom-and-property function: the stop inputs are `{zoom, value}` objects. */
    @Test
    fun zoomAndPropertyFunctionNestsAPropertyFunctionInsideAZoomCurve() {
        val expr = json.decodeFromString<ExpressionOrValue<Double>>(
            """{"type":"exponential","property":"rank","base":1,"stops":[
                 [{"zoom":0,"value":0},0],[{"zoom":0,"value":10},10],
                 [{"zoom":10,"value":0},0],[{"zoom":10,"value":10},100]]}"""
        )
        assertEquals(5.0, expr.processAsDouble(feature("rank" to 5.0), zoom = 0.0))
        assertEquals(50.0, expr.processAsDouble(feature("rank" to 5.0), zoom = 10.0))
        // Halfway in zoom between the two property curves.
        assertEquals(27.5, expr.processAsDouble(feature("rank" to 5.0), zoom = 5.0))
    }

    /**
     * A constant legacy function must stay constant.
     *
     * Upstream's `fixupDegenerateStepCurve` reads the array slot it has just written and appends a
     * second `0`, which makes the curve evaluate to `0` above zoom 0; this port repeats the output
     * instead. See the note on that function.
     */
    @Test
    fun aSingleStopFunctionIsConstant() {
        val expr = json.decodeFromString<ExpressionOrValue<String>>("""{"stops":[[0,"only"]]}""")
        assertEquals("only", expr.processAsString(zoom = 0.0))
        assertEquals("only", expr.processAsString(zoom = 10.0))
        assertEquals("only", expr.processAsString(zoom = 22.0))
    }
}
