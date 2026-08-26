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

/**
 * `convertLegacyFunction` is the one ported file with no upstream test at all — upstream has no
 * `function/convert.test.ts`, and its 79 `function/index.test.ts` cases target `createFunction`, a
 * separate direct evaluator this port does not have.
 *
 * [LegacyFunctionTest] covers what the converted expressions *evaluate to*; this covers the JSON
 * they are converted *into*, which is the part a reader has to trust when comparing against
 * `maplibre-style-spec/src/function/convert.ts`.
 */
class LegacyFunctionConversionTest {

    private val numberSpec = StylePropertySpec(expectedType = NumberType, supportsInterpolation = true)
    private val stringSpec = StylePropertySpec(expectedType = StringType, supportsInterpolation = false)

    private fun convert(source: String, spec: StylePropertySpec): Any? {
        @Suppress("UNCHECKED_CAST")
        val parameters = expr(source) as Map<String, Any?>
        return convertLegacyFunction(parameters, spec)
    }

    @Test
    fun `identity becomes a bare get`() {
        assertEquals(
            listOf("get", "color"),
            convert("""{"type":"identity","property":"color"}""", numberSpec),
        )
    }

    /** A string property asserts rather than coerces, to preserve legacy semantics. */
    @Test
    fun `identity on a string property inserts an assertion`() {
        assertEquals(
            listOf("string", listOf("get", "name")),
            convert("""{"type":"identity","property":"name"}""", stringSpec),
        )
    }

    @Test
    fun `zoom stops on an interpolatable property become an interpolate`() {
        assertEquals(
            listOf("interpolate", listOf("linear"), listOf("zoom"), 0.0, 1.0, 10.0, 11.0),
            convert("""{"stops":[[0,1],[10,11]]}""", numberSpec),
        )
    }

    @Test
    fun `a base other than one becomes an exponential interpolation`() {
        assertEquals(
            listOf("interpolate", listOf("exponential", 2.0), listOf("zoom"), 0.0, 1.0, 10.0, 11.0),
            convert("""{"base":2,"stops":[[0,1],[10,11]]}""", numberSpec),
        )
    }

    /** A non-interpolatable property gets a step, and the first stop's input is dropped. */
    @Test
    fun `zoom stops on a string property become a step`() {
        assertEquals(
            listOf("step", listOf("zoom"), "a", 4.0, "b"),
            convert("""{"stops":[[2,"a"],[4,"b"]]}""", stringSpec),
        )
    }

    @Test
    fun `a property function of type interval becomes a step on the property`() {
        assertEquals(
            listOf("step", listOf("number", listOf("get", "rank")), 1.0, 5.0, 2.0),
            convert("""{"type":"interval","property":"rank","stops":[[0,1],[5,2]]}""", numberSpec),
        )
    }

    @Test
    fun `a default wraps the curve in a typeof guard`() {
        assertEquals(
            listOf(
                "case",
                listOf("==", listOf("typeof", listOf("get", "rank")), "number"),
                listOf("step", listOf("number", listOf("get", "rank")), 1.0, 5.0, 2.0),
                9.0,
            ),
            convert(
                """{"type":"interval","property":"rank","stops":[[0,1],[5,2]],"default":9}""",
                numberSpec,
            ),
        )
    }

    @Test
    fun `categorical becomes a match with the fallback last`() {
        assertEquals(
            listOf("match", listOf("get", "kind"), "a", 1.0, "b", 2.0, 3.0),
            convert(
                """{"type":"categorical","property":"kind","stops":[["a",1],["b",2]],"default":3}""",
                numberSpec,
            ),
        )
    }

    @Test
    fun `categorical with boolean stops becomes a case`() {
        assertEquals(
            listOf(
                "case",
                listOf("==", listOf("get", "flag"), true), 1.0,
                listOf("==", listOf("get", "flag"), false), 2.0,
                3.0,
            ),
            convert(
                """{"type":"categorical","property":"flag","stops":[[true,1],[false,2]],"default":3}""",
                numberSpec,
            ),
        )
    }

    /**
     * With no `default` and no spec default, a neutral literal is substituted so the `match` still
     * type-checks against the property's type. Upstream always has a spec default to fall back on;
     * MapCompose has no per-property metadata table. See `convertLegacyFunction`.
     */
    @Test
    fun `categorical without a default gets a neutral fallback`() {
        assertEquals(
            listOf("match", listOf("get", "kind"), "a", 1.0, "b", 2.0, 0.0),
            convert(
                """{"type":"categorical","property":"kind","stops":[["a",1],["b",2]]}""",
                numberSpec,
            ),
        )
    }

    @Test
    fun `a zoom-and-property function nests a property curve inside a zoom curve`() {
        val converted = convert(
            """{"type":"exponential","property":"rank","base":1,"stops":[
                 [{"zoom":0,"value":0},0],[{"zoom":0,"value":10},10],
                 [{"zoom":10,"value":0},0],[{"zoom":10,"value":10},100]]}""",
            numberSpec,
        ) as List<*>

        assertEquals("interpolate", converted[0])
        assertEquals(listOf("linear"), converted[1])
        assertEquals(listOf("zoom"), converted[2])
        // One zoom stop per distinct zoom, each holding a full property curve.
        assertEquals(0.0, converted[3])
        assertEquals(10.0, converted[5])
        assertEquals("interpolate", (converted[4] as List<*>)[0])
        assertEquals("interpolate", (converted[6] as List<*>)[0])
    }

    /**
     * Deliberate divergence: upstream's `fixupDegenerateStepCurve` reads the array slot it has just
     * written, appending the `0` it pushed rather than the output, which makes every constant legacy
     * function evaluate to `0` above zoom 0. This port repeats the output instead.
     */
    @Test
    fun `a degenerate step curve repeats its output rather than zeroing it`() {
        assertEquals(
            listOf("step", listOf("zoom"), "only", 0.0, "only"),
            convert("""{"stops":[[0,"only"]]}""", stringSpec),
        )
    }

    /** Token strings are left alone unless the property spec opts in. */
    @Test
    fun `token strings are only converted when the spec asks for it`() {
        assertEquals(
            listOf("step", listOf("zoom"), "{name}", 4.0, "{ref}"),
            convert("""{"stops":[[2,"{name}"],[4,"{ref}"]]}""", stringSpec),
        )

        val withTokens = stringSpec.copy(tokens = true)
        assertEquals(
            listOf(
                "step", listOf("zoom"),
                listOf("to-string", listOf("get", "name")),
                4.0,
                listOf("to-string", listOf("get", "ref")),
            ),
            convert("""{"stops":[[2,"{name}"],[4,"{ref}"]]}""", withTokens),
        )
    }
}
