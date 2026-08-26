package ovh.plrapps.mapcompose.vector.spec.style.filter

import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.captureWarnings
import ovh.plrapps.mapcompose.vector.spec.style.expression.expr
import ovh.plrapps.mapcompose.vector.spec.style.expression.geoJsonFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `maplibre-style-spec/src/feature_filter/feature_filter.test.ts`.
 *
 * Three adaptations run through the whole file:
 *
 * 1. **Compile failures are values, not throws.** Upstream's `featureFilter` throws when the filter
 *    does not compile; this port returns an [ExpressionResult.Error] so the style serializer can
 *    record a diagnostic and carry on. Tests that upstream writes as `expect(...).toThrow()` assert
 *    the error result and its message instead.
 * 2. **Geometry types are strings.** Upstream's fixtures use the MVT numeric encoding
 *    (`{type: 1}`); `EvaluationContext.geometryType` maps those through
 *    `['Unknown', 'Point', 'LineString', 'Polygon']`, so `1` is written here as `"Point"`.
 * 3. **`undefined` becomes an absent key.** Kotlin has no `undefined`, and the distinction only
 *    matters to `has`: upstream treats `{foo: undefined}` as absent and `{foo: null}` as present.
 *
 * Not transcribed: the `convert legacy filters to expressions` block and the second half of the
 * `legacy filter tests` loop, both of which target `feature_filter/convert.ts` — a *second*,
 * separately exported converter that this port does not have. The converter this port does have,
 * `convertLegacyFilter`, is the one local to `feature_filter/index.ts`, and it is what the first
 * half of that loop exercises.
 */
class FeatureFilterUpstreamTest {

    // region harness

    private fun compile(filter: Any?, globalState: Map<String, Any?>? = null): FeatureFilter? =
        when (val r = featureFilter(filter, "layers[0].filter", globalState)) {
            is ExpressionResult.Success -> r.value
            is ExpressionResult.Error -> throw AssertionError("failed to compile: ${r.errors}")
        }

    private fun compileErrors(filter: Any?): List<String> =
        when (val r = featureFilter(filter, "layers[0].filter")) {
            is ExpressionResult.Success -> throw AssertionError("expected a compile error, got ${r.value}")
            is ExpressionResult.Error -> r.errors.map { "${it.key}: ${it.message}" }
        }

    /** `f({zoom}, {properties})` in upstream's notation. */
    private fun FeatureFilter?.matches(
        properties: Map<String, Any?> = emptyMap(),
        zoom: Double = 0.0,
        type: String = "Unknown",
        id: Any? = null,
        canonical: CanonicalTileId? = null,
    ): Boolean {
        if (this == null) return true
        return filter(
            GlobalProperties(zoom = zoom),
            EvalFeature(type = type, id = id, properties = properties),
            canonical,
        )
    }

    private fun props(vararg entries: Pair<String, Any?>): Map<String, Any?> = entries.toMap()

    // endregion

    // region describe('filter') — expression syntax

    @Test
    fun `expression zoom`() {
        val f = compile(expr("""[">=",["number",["get","x"]],["zoom"]]"""))
        assertFalse(f.matches(props("x" to 0.0), zoom = 1.0))
        assertTrue(f.matches(props("x" to 1.5), zoom = 1.0))
        assertTrue(f.matches(props("x" to 2.5), zoom = 1.0))
        assertFalse(f.matches(props("x" to 0.0), zoom = 2.0))
        assertFalse(f.matches(props("x" to 1.5), zoom = 2.0))
        assertTrue(f.matches(props("x" to 2.5), zoom = 2.0))
    }

    @Test
    fun `expression compare two properties`() {
        val f = compile(expr("""["==",["string",["get","x"]],["string",["get","y"]]]"""))
        captureWarnings {
            assertFalse(f.matches(props("x" to 1.0, "y" to 1.0)))
            assertTrue(f.matches(props("x" to "1", "y" to "1")))
            assertTrue(f.matches(props("x" to "same", "y" to "same")))
            assertFalse(f.matches(props("x" to null)))
            assertFalse(f.matches(props()))
        }
    }

    @Test
    fun `expression collator comparison`() {
        val caseSensitive = compile(
            expr("""["==",["string",["get","x"]],["string",["get","y"]],["collator",{"case-sensitive":true}]]""")
        )
        assertFalse(caseSensitive.matches(props("x" to "a", "y" to "b")))
        assertFalse(caseSensitive.matches(props("x" to "a", "y" to "A")))
        assertTrue(caseSensitive.matches(props("x" to "a", "y" to "a")))

        val caseInsensitive = compile(
            expr("""["==",["string",["get","x"]],["string",["get","y"]],["collator",{"case-sensitive":false}]]""")
        )
        assertFalse(caseInsensitive.matches(props("x" to "a", "y" to "b")))
        assertTrue(caseInsensitive.matches(props("x" to "a", "y" to "A")))
        assertTrue(caseInsensitive.matches(props("x" to "a", "y" to "a")))
    }

    @Test
    fun `expression any and all`() {
        assertTrue(compile(expr("""["all"]""")).matches())
        assertTrue(compile(expr("""["all",true]""")).matches())
        assertFalse(compile(expr("""["all",true,false]""")).matches())
        assertTrue(compile(expr("""["all",true,true]""")).matches())
        assertFalse(compile(expr("""["any"]""")).matches())
        assertTrue(compile(expr("""["any",true]""")).matches())
        assertTrue(compile(expr("""["any",true,false]""")).matches())
        assertFalse(compile(expr("""["any",false,false]""")).matches())
    }

    @Test
    fun `expression literal`() {
        assertTrue(compile(expr("""["literal",true]""")).matches())
        assertFalse(compile(expr("""["literal",false]""")).matches())
    }

    @Test
    fun `expression match`() {
        val f = compile(expr("""["match",["get","x"],["a","b","c"],true,false]"""))
        assertTrue(f.matches(props("x" to "a")))
        assertTrue(f.matches(props("x" to "c")))
        assertFalse(f.matches(props("x" to "d")))
    }

    @Test
    fun `expression type error`() {
        assertTrue(
            compileErrors(expr("""["==",["number",["get","x"]],["string",["get","y"]]]"""))
                .any { it.endsWith(": Cannot compare types 'number' and 'string'.") },
        )
        assertTrue(
            compileErrors(expr("""["number",["get","x"]]"""))
                .any { it.endsWith(": Expected boolean but found number instead.") },
        )
        // …but a boolean assertion is fine.
        compile(expr("""["boolean",["get","x"]]"""))
    }

    @Test
    fun `expression within`() {
        val withinFilter = compile(
            expr("""["within",{"type":"Polygon","coordinates":[[[0,0],[5,0],[5,5],[0,5],[0,0]]]}]""")
        )!!
        assertTrue(withinFilter.needGeometry)

        val canonical = CanonicalTileId(z = 3, x = 3, y = 3)
        fun inTile(type: String, coordinates: Any?): Boolean = withinFilter.filter(
            GlobalProperties(zoom = 3.0),
            geoJsonFeature(geometryType = type, coordinates = coordinates, canonical = canonical),
            canonical,
        )

        assertTrue(inTile("Point", listOf(2.0, 2.0)))
        assertFalse(inTile("Point", listOf(6.0, 6.0)))
        assertFalse(inTile("Point", listOf(5.0, 5.0)))
        assertTrue(inTile("LineString", listOf(listOf(2.0, 2.0), listOf(3.0, 3.0))))
        assertFalse(inTile("LineString", listOf(listOf(6.0, 6.0), listOf(2.0, 2.0))))
        assertFalse(inTile("LineString", listOf(listOf(5.0, 5.0), listOf(2.0, 2.0))))
    }

    @Test
    fun `expression global-state`() {
        val f = compile(expr("""["==",["global-state","x"],["get","x"]]"""), mapOf("x" to 1.0))
        assertTrue(f.matches(props("x" to 1.0)))
        assertFalse(f.matches(props("x" to 2.0)))
    }

    @Test
    fun `getGlobalStateRefs returns global-state keys`() {
        val f = compile(expr("""["==",["global-state","x"],["zoom"]]"""))!!
        assertEquals(setOf("x"), f.getGlobalStateRefs())
    }

    // endregion

    // region describe('legacy filter detection')

    @Test
    fun `definitely legacy filters`() {
        // Expressions with more than two arguments.
        assertFalse(isExpressionFilter(expr("""["in","color","red","blue"]""")))
        // Expressions where the second argument is not a string or array.
        assertFalse(isExpressionFilter(expr("""["in","value",42]""")))
        assertFalse(isExpressionFilter(expr("""["in","value",true]""")))
    }

    @Test
    fun `ambiguous value`() {
        // Should err on the side of reporting as a legacy filter. Style authors can force filters
        // by using a literal expression as the first argument.
        assertFalse(isExpressionFilter(expr("""["in","color","red"]""")))
    }

    @Test
    fun `definitely expressions`() {
        assertTrue(isExpressionFilter(expr("""["in",["get","color"],"reddish"]""")))
        assertTrue(isExpressionFilter(expr("""["in",["get","color"],["red","blue"]]""")))
        assertTrue(isExpressionFilter(expr("""["in",42,42]""")))
        assertTrue(isExpressionFilter(expr("""["in",true,true]""")))
        assertTrue(isExpressionFilter(expr("""["in","red",["get","colors"]]""")))
    }

    // endregion

    // region describe('legacy filter tests')

    @Test
    fun `degenerate`() {
        assertNull(compile(null))
    }

    @Test
    fun `equals string`() {
        val f = compile(expr("""["==","foo","bar"]"""))
        assertTrue(f.matches(props("foo" to "bar")))
        assertFalse(f.matches(props("foo" to "baz")))
    }

    @Test
    fun `equals number`() {
        val f = compile(expr("""["==","foo",0]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to 1.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertFalse(f.matches(props()))
    }

    @Test
    fun `equals null`() {
        val f = compile(expr("""["==","foo",null]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to 1.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertTrue(f.matches(props("foo" to null)))
        assertFalse(f.matches(props()))
    }

    @Test
    fun `equals dollar type`() {
        val f = compile(expr("""["==","${'$'}type","LineString"]"""))
        assertFalse(f.matches(type = "Point"))
        assertTrue(f.matches(type = "LineString"))
    }

    @Test
    fun `equals dollar id`() {
        val f = compile(expr("""["==","${'$'}id",1234]"""))
        assertTrue(f.matches(id = 1234.0))
        assertFalse(f.matches(id = "1234"))
        assertFalse(f.matches(props("id" to 1234.0)))
    }

    @Test
    fun `not equals string`() {
        val f = compile(expr("""["!=","foo","bar"]"""))
        assertFalse(f.matches(props("foo" to "bar")))
        assertTrue(f.matches(props("foo" to "baz")))
    }

    @Test
    fun `not equals number`() {
        val f = compile(expr("""["!=","foo",0]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertTrue(f.matches(props("foo" to true)))
        assertTrue(f.matches(props("foo" to false)))
        assertTrue(f.matches(props("foo" to null)))
        assertTrue(f.matches(props()))
    }

    @Test
    fun `not equals null`() {
        val f = compile(expr("""["!=","foo",null]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertTrue(f.matches(props("foo" to true)))
        assertTrue(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertTrue(f.matches(props()))
    }

    @Test
    fun `not equals dollar type`() {
        val f = compile(expr("""["!=","${'$'}type","LineString"]"""))
        assertTrue(f.matches(type = "Point"))
        assertFalse(f.matches(type = "LineString"))
    }

    /** The four ordering operators share one table: same inputs, different expected outcomes. */
    private fun assertOrdering(
        filter: String,
        gt: Boolean,
        eq: Boolean,
        lt: Boolean,
        numeric: Boolean,
    ) {
        val f = compile(expr(filter))
        if (numeric) {
            assertEquals(gt, f.matches(props("foo" to 1.0)))
            assertEquals(eq, f.matches(props("foo" to 0.0)))
            assertEquals(lt, f.matches(props("foo" to -1.0)))
            // A string property never compares against a numeric bound.
            assertFalse(f.matches(props("foo" to "1")))
            assertFalse(f.matches(props("foo" to "0")))
            assertFalse(f.matches(props("foo" to "-1")))
        } else {
            assertFalse(f.matches(props("foo" to -1.0)))
            assertFalse(f.matches(props("foo" to 0.0)))
            assertFalse(f.matches(props("foo" to 1.0)))
            assertEquals(gt, f.matches(props("foo" to "1")))
            assertEquals(eq, f.matches(props("foo" to "0")))
            assertEquals(lt, f.matches(props("foo" to "-1")))
        }
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertFalse(f.matches(props()))
    }

    @Test
    fun `less than number`() = assertOrdering("""["<","foo",0]""", gt = false, eq = false, lt = true, numeric = true)

    @Test
    fun `less than string`() = assertOrdering("""["<","foo","0"]""", gt = false, eq = false, lt = true, numeric = false)

    @Test
    fun `less than or equal number`() =
        assertOrdering("""["<=","foo",0]""", gt = false, eq = true, lt = true, numeric = true)

    @Test
    fun `less than or equal string`() =
        assertOrdering("""["<=","foo","0"]""", gt = false, eq = true, lt = true, numeric = false)

    @Test
    fun `greater than number`() =
        assertOrdering("""[">","foo",0]""", gt = true, eq = false, lt = false, numeric = true)

    @Test
    fun `greater than string`() =
        assertOrdering("""[">","foo","0"]""", gt = true, eq = false, lt = false, numeric = false)

    @Test
    fun `greater than or equal number`() =
        assertOrdering("""[">=","foo",0]""", gt = true, eq = true, lt = false, numeric = true)

    @Test
    fun `greater than or equal string`() =
        assertOrdering("""[">=","foo","0"]""", gt = true, eq = true, lt = false, numeric = false)

    @Test
    fun `in degenerate`() {
        val f = compile(expr("""["in","foo"]"""))
        assertFalse(f.matches(props("foo" to 1.0)))
    }

    @Test
    fun `in string`() {
        val f = compile(expr("""["in","foo","0"]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertFalse(f.matches(props()))
    }

    @Test
    fun `in number`() {
        val f = compile(expr("""["in","foo",0]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertFalse(f.matches(props()))
    }

    @Test
    fun `in null`() {
        val f = compile(expr("""["in","foo",null]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to true)))
        assertFalse(f.matches(props("foo" to false)))
        assertTrue(f.matches(props("foo" to null)))
    }

    @Test
    fun `in multiple`() {
        val f = compile(expr("""["in","foo",0,1]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertFalse(f.matches(props("foo" to 3.0)))
    }

    /** Over 200 homogeneous values, the converter emits `filter-in-large`, which binary-searches. */
    @Test
    fun `in large multiple`() {
        val values: List<Any?> = (0 until 2000).map { it.toDouble() }.reversed()
        val f = compile(listOf("in", "foo") + values)
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to 1999.0)))
        assertFalse(f.matches(props("foo" to 2000.0)))
    }

    /** Mixed types cannot be binary-searched, so the converter falls back to `filter-in-small`. */
    @Test
    fun `in large multiple heterogeneous`() {
        val values: List<Any?> = listOf("b") + (0 until 2000).map { it.toDouble() } + listOf("a")
        val f = compile(listOf("in", "foo") + values)
        assertTrue(f.matches(props("foo" to "b")))
        assertTrue(f.matches(props("foo" to "a")))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to 1999.0)))
        assertFalse(f.matches(props("foo" to 2000.0)))
    }

    @Test
    fun `in dollar type`() {
        val f = compile(expr("""["in","${'$'}type","LineString","Polygon"]"""))
        assertFalse(f.matches(type = "Point"))
        assertTrue(f.matches(type = "LineString"))
        assertTrue(f.matches(type = "Polygon"))

        val f1 = compile(expr("""["in","${'$'}type","Polygon","LineString","Point"]"""))
        assertTrue(f1.matches(type = "Point"))
        assertTrue(f1.matches(type = "LineString"))
        assertTrue(f1.matches(type = "Polygon"))
    }

    @Test
    fun `not in degenerate`() {
        val f = compile(expr("""["!in","foo"]"""))
        assertTrue(f.matches(props("foo" to 1.0)))
    }

    @Test
    fun `not in string`() {
        val f = compile(expr("""["!in","foo","0"]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertTrue(f.matches(props("foo" to null)))
        assertTrue(f.matches(props()))
    }

    @Test
    fun `not in number`() {
        val f = compile(expr("""["!in","foo",0]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertTrue(f.matches(props("foo" to null)))
        assertTrue(f.matches(props()))
    }

    @Test
    fun `not in null`() {
        val f = compile(expr("""["!in","foo",null]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to null)))
    }

    @Test
    fun `not in multiple`() {
        val f = compile(expr("""["!in","foo",0,1]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to 3.0)))
    }

    @Test
    fun `not in large multiple`() {
        val values: List<Any?> = (0 until 2000).map { it.toDouble() }
        val f = compile(listOf("!in", "foo") + values)
        assertFalse(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to 1.0)))
        assertFalse(f.matches(props("foo" to 1999.0)))
        assertTrue(f.matches(props("foo" to 2000.0)))
    }

    @Test
    fun `not in dollar type`() {
        val f = compile(expr("""["!in","${'$'}type","LineString","Polygon"]"""))
        assertTrue(f.matches(type = "Point"))
        assertFalse(f.matches(type = "LineString"))
        assertFalse(f.matches(type = "Polygon"))
    }

    @Test
    fun `any`() {
        assertFalse(compile(expr("""["any"]""")).matches(props("foo" to 1.0)))
        assertTrue(compile(expr("""["any",["==","foo",1]]""")).matches(props("foo" to 1.0)))
        assertFalse(compile(expr("""["any",["==","foo",0]]""")).matches(props("foo" to 1.0)))
        assertTrue(compile(expr("""["any",["==","foo",0],["==","foo",1]]""")).matches(props("foo" to 1.0)))
    }

    @Test
    fun `all`() {
        assertTrue(compile(expr("""["all"]""")).matches(props("foo" to 1.0)))
        assertTrue(compile(expr("""["all",["==","foo",1]]""")).matches(props("foo" to 1.0)))
        assertFalse(compile(expr("""["all",["==","foo",0]]""")).matches(props("foo" to 1.0)))
        assertFalse(compile(expr("""["all",["==","foo",0],["==","foo",1]]""")).matches(props("foo" to 1.0)))
    }

    @Test
    fun `none`() {
        assertTrue(compile(expr("""["none"]""")).matches(props("foo" to 1.0)))
        assertFalse(compile(expr("""["none",["==","foo",1]]""")).matches(props("foo" to 1.0)))
        assertTrue(compile(expr("""["none",["==","foo",0]]""")).matches(props("foo" to 1.0)))
        assertFalse(compile(expr("""["none",["==","foo",0],["==","foo",1]]""")).matches(props("foo" to 1.0)))
    }

    @Test
    fun `has`() {
        val f = compile(expr("""["has","foo"]"""))
        assertTrue(f.matches(props("foo" to 0.0)))
        assertTrue(f.matches(props("foo" to 1.0)))
        assertTrue(f.matches(props("foo" to "0")))
        assertTrue(f.matches(props("foo" to true)))
        assertTrue(f.matches(props("foo" to false)))
        // null is a valid JSON value, so the property exists.
        assertTrue(f.matches(props("foo" to null)))
        // undefined means the property was never set; here, an absent key.
        assertFalse(f.matches(props()))
    }

    @Test
    fun `not has`() {
        val f = compile(expr("""["!has","foo"]"""))
        assertFalse(f.matches(props("foo" to 0.0)))
        assertFalse(f.matches(props("foo" to 1.0)))
        assertFalse(f.matches(props("foo" to "0")))
        assertFalse(f.matches(props("foo" to false)))
        assertFalse(f.matches(props("foo" to null)))
        assertTrue(f.matches(props()))
    }

    @Test
    fun `pure legacy filter using has still matches the right features`() {
        val filter = expr(
            """["all",["==","${'$'}type","LineString"],["all",["==","class","rail"],["has","service"]]]"""
        )

        val (f, warnings) = captureWarnings { compile(filter) }
        // A filter that is legacy throughout must not trip the mixed-syntax diagnostic.
        assertTrue(warnings.isEmpty(), "unexpected warnings: $warnings")

        assertTrue(f.matches(props("class" to "rail", "service" to "yard"), type = "LineString"))
        assertFalse(f.matches(props("class" to "rail"), type = "LineString"))
        assertFalse(f.matches(props("class" to "road", "service" to "yard"), type = "LineString"))
    }

    // endregion

    // region describe('global-state in filter')

    @Test
    fun `basic global-state equality filter`() {
        val globalState = mapOf<String, Any?>("activeId" to "track1")
        val f = compile(expr("""["==",["get","id"],["global-state","activeId"]]"""), globalState)!!

        assertEquals(setOf("activeId"), f.getGlobalStateRefs())
        assertTrue(f.matches(props("id" to "track1")))
        assertFalse(f.matches(props("id" to "track2")))
    }

    @Test
    fun `global-state filter updates reactively when state is mutated`() {
        val globalState = mutableMapOf<String, Any?>("activeId" to "none")
        val f = compile(expr("""["==",["get","id"],["global-state","activeId"]]"""), globalState)

        assertFalse(f.matches(props("id" to "track1")))

        // Mutate the state the filter was built with (simulates setGlobalStateProperty).
        globalState["activeId"] = "track1"

        assertTrue(f.matches(props("id" to "track1")))
        assertFalse(f.matches(props("id" to "track2")))
    }

    @Test
    fun `global-state in case filter expression`() {
        val f = compile(
            expr(
                """["case",["==",["get","id"],["global-state","activeId"]],true,
                   ["any",["==",["get","role"],"start"],["==",["get","role"],"end"]]]"""
            ),
            mapOf("activeId" to "track1"),
        )

        // Active track: every role passes.
        assertTrue(f.matches(props("id" to "track1", "role" to "mid")))
        assertTrue(f.matches(props("id" to "track1", "role" to "insert")))
        assertTrue(f.matches(props("id" to "track1", "role" to "start")))

        // Inactive track: only start/end pass.
        assertFalse(f.matches(props("id" to "track2", "role" to "mid")))
        assertFalse(f.matches(props("id" to "track2", "role" to "insert")))
        assertTrue(f.matches(props("id" to "track2", "role" to "start")))
        assertTrue(f.matches(props("id" to "track2", "role" to "end")))
    }

    @Test
    fun `isExpressionFilter recognizes filters mixing dollar type with expression operators`() {
        // The pattern from upstream issue #1544:
        //   ['all', ['==', '$type', 'Point'], ['case', isActive, true, fallback]]
        // isExpressionFilter used to return false because ['==', '$type', 'Point'] looks legacy
        // (3 args, no arrays), sending the whole filter through the converter, which silently
        // mangles the expression operators before the mixed-syntax diagnostic can run.
        val filter = expr(
            """["all",["==","${'$'}type","Point"],
               ["case",["==",["get","id"],["global-state","activeTrackId"]],true,
                ["any",["==",["get","role"],"start"],["==",["get","role"],"end"]]]]"""
        )
        assertTrue(isExpressionFilter(filter))
    }

    @Test
    fun `mixed filter warns about unsupported legacy special-key operators`() {
        val filter = expr("""["all",[">","${'$'}type","Point"],["==",["global-state","active"],true]]""")
        val (_, warnings) = captureWarnings {
            featureFilter(filter, "layers[0].filter", mapOf("active" to true))
        }
        assertEquals(
            listOf("layers[0].filter[1]: \"\$type\" cannot be use with operator \">\""),
            warnings,
        )
    }

    @Test
    fun `none is never an expression so it is always converted from legacy syntax`() {
        val filter = expr(
            """["none",["==","${'$'}type","Polygon"],
               ["case",["==",["get","id"],["global-state","activeTrackId"]],true,false]]"""
        )
        assertFalse(isExpressionFilter(filter))
        captureWarnings { featureFilter(filter, "layers[0].filter", mapOf("activeTrackId" to "track1")) }
    }

    @Test
    fun `suggests a valid expression for mixed dollar type not-equals filter`() {
        val filter = expr("""["all",["!=","${'$'}type","LineString"],["==",["global-state","active"],true]]""")
        val (_, warnings) = captureWarnings {
            featureFilter(filter, "layers[0].filter", mapOf("active" to true))
        }
        assertEquals(
            listOf(
                "layers[0].filter[1]: Mixing deprecated filter syntax with expression syntax is not " +
                        "supported. Replace [\"!=\",\"\$type\",\"LineString\"] with " +
                        "[\"!=\",[\"geometry-type\"],\"LineString\"]."
            ),
            warnings,
        )
    }

    // endregion
}
