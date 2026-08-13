package ovh.plrapps.mapcompose.vector.spec.style.filter

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layer-filter front end: syntax classification, legacy-to-expression conversion, and
 * evaluation. A filter is just a boolean expression, so operator behaviour itself is covered by
 * `ExpressionConformanceTest`.
 */
class FeatureFilterTest {

    private fun compile(filter: Any?): FeatureFilter? =
        when (val r = featureFilter(filter, rootKey = "layers[0].filter")) {
            is ExpressionResult.Success -> r.value
            is ExpressionResult.Error -> throw AssertionError("failed to compile: ${r.errors}")
        }

    private fun matches(
        filter: Any?,
        properties: Map<String, Any?> = emptyMap(),
        type: String = "Point",
        id: Any? = null,
        zoom: Double = 10.0,
    ): Boolean {
        val compiled = compile(filter) ?: return true
        return compiled.filter(
            GlobalProperties(zoom = zoom),
            EvalFeature(type = type, id = id, properties = properties),
        )
    }

    // region classification

    @Test
    fun legacyOnlyOperatorsAreClassifiedAsLegacy() {
        assertEquals(FilterClassification.LEGACY, classifyFilter(listOf("!in", "class", "a")))
        assertEquals(FilterClassification.LEGACY, classifyFilter(listOf("!has", "class")))
        assertEquals(FilterClassification.LEGACY, classifyFilter(listOf("none", listOf("==", "a", 1.0))))
        assertEquals(FilterClassification.LEGACY, classifyFilter(listOf("==", "class", "street")))
    }

    @Test
    fun expressionOnlyFormsAreClassifiedAsExpression() {
        assertEquals(FilterClassification.EXPRESSION, classifyFilter(listOf("==", listOf("get", "class"), "street")))
        assertEquals(FilterClassification.EXPRESSION, classifyFilter(listOf("!", listOf("has", "class"))))
        assertEquals(
            FilterClassification.EXPRESSION,
            classifyFilter(listOf("in", listOf("get", "c"), listOf("literal", listOf("a", "b")))),
        )
    }

    /**
     * A node that reads the same in both syntaxes must not decide the syntax of the tree it sits
     * in — otherwise `["all", ["has","x"], ["==", ["get","y"], 1]]` would be misread as legacy.
     */
    @Test
    fun neutralNodesDoNotDecideTheTreeSyntax() {
        assertEquals(FilterClassification.NEUTRAL, classifyFilter(listOf("has", "class")))
        assertEquals(FilterClassification.NEUTRAL, classifyFilter(true))
        assertEquals(
            FilterClassification.EXPRESSION,
            classifyFilter(listOf("all", listOf("has", "x"), listOf("==", listOf("get", "y"), 1.0))),
        )
        assertEquals(
            FilterClassification.LEGACY,
            classifyFilter(listOf("all", listOf("has", "x"), listOf("==", "y", 1.0))),
        )
    }

    // endregion

    // region legacy conversion

    @Test
    fun legacyComparisonsConvertToInternalFilterOperators() {
        assertEquals(listOf("filter-==", "class", "street"), convertLegacyFilter(listOf("==", "class", "street")))
        assertEquals(
            listOf("!", listOf("filter-==", "class", "street")),
            convertLegacyFilter(listOf("!=", "class", "street")),
        )
        assertEquals(listOf("filter-<", "rank", 3.0), convertLegacyFilter(listOf("<", "rank", 3.0)))
    }

    @Test
    fun dollarTypeAndDollarIdMapToGeometryTypeAndId() {
        assertEquals(listOf("filter-type-==", "LineString"), convertLegacyFilter(listOf("==", "\$type", "LineString")))
        assertEquals(listOf("filter-id-==", 5.0), convertLegacyFilter(listOf("==", "\$id", 5.0)))
        assertEquals(true, convertLegacyFilter(listOf("has", "\$type")))
        assertEquals(listOf("filter-has-id"), convertLegacyFilter(listOf("has", "\$id")))
    }

    @Test
    fun legacyNoneBecomesAllOfNegations() {
        assertEquals(
            listOf("all", listOf("!", listOf("filter-==", "a", 1.0)), listOf("!", listOf("filter-==", "b", 2.0))),
            convertLegacyFilter(listOf("none", listOf("==", "a", 1.0), listOf("==", "b", 2.0))),
        )
    }

    @Test
    fun legacyNotInBecomesNegatedIn() {
        assertEquals(
            listOf("!", listOf("filter-in-small", "class", listOf("literal", listOf("a", "b")))),
            convertLegacyFilter(listOf("!in", "class", "a", "b")),
        )
    }

    // endregion

    // region evaluation

    @Test
    fun everyLegacyOperatorEvaluates() {
        assertTrue(matches(listOf("==", "class", "street"), mapOf("class" to "street")))
        assertFalse(matches(listOf("==", "class", "street"), mapOf("class" to "path")))
        assertTrue(matches(listOf("!=", "class", "street"), mapOf("class" to "path")))
        assertTrue(matches(listOf("<", "rank", 3.0), mapOf("rank" to 1.0)))
        assertTrue(matches(listOf("<=", "rank", 3.0), mapOf("rank" to 3.0)))
        assertTrue(matches(listOf(">", "rank", 3.0), mapOf("rank" to 4.0)))
        assertTrue(matches(listOf(">=", "rank", 3.0), mapOf("rank" to 3.0)))
        assertTrue(matches(listOf("in", "class", "a", "b"), mapOf("class" to "b")))
        assertFalse(matches(listOf("in", "class", "a", "b"), mapOf("class" to "c")))
        assertTrue(matches(listOf("!in", "class", "a", "b"), mapOf("class" to "c")))
        assertTrue(matches(listOf("has", "class"), mapOf("class" to "x")))
        assertFalse(matches(listOf("has", "class"), emptyMap()))
        assertTrue(matches(listOf("!has", "class"), emptyMap()))
        assertTrue(matches(listOf("all", listOf("has", "a"), listOf("has", "b")), mapOf("a" to 1.0, "b" to 2.0)))
        assertFalse(matches(listOf("all", listOf("has", "a"), listOf("has", "b")), mapOf("a" to 1.0)))
        assertTrue(matches(listOf("any", listOf("has", "a"), listOf("has", "b")), mapOf("b" to 2.0)))
        assertTrue(matches(listOf("none", listOf("has", "a")), emptyMap()))
    }

    @Test
    fun legacyTypeAndIdFiltersEvaluate() {
        assertTrue(matches(listOf("==", "\$type", "LineString"), type = "LineString"))
        assertFalse(matches(listOf("==", "\$type", "LineString"), type = "Polygon"))
        assertTrue(matches(listOf("in", "\$type", "Point", "LineString"), type = "Point"))
        assertTrue(matches(listOf("==", "\$id", 5.0), id = 5.0))
        assertTrue(matches(listOf("has", "\$id"), id = 1.0))
        assertFalse(matches(listOf("has", "\$id")))
    }

    /**
     * Legacy semantics: a comparison against a property of the wrong type yields false rather than
     * an error, so the surrounding `any` can still succeed on another branch.
     */
    @Test
    fun legacyOrderingAgainstAWrongTypedPropertyIsFalseNotAnError() {
        assertFalse(matches(listOf(">", "rank", 3.0), mapOf("rank" to "not a number")))
        assertTrue(
            matches(
                listOf("any", listOf(">", "rank", 3.0), listOf("==", "class", "street")),
                mapOf("rank" to "not a number", "class" to "street"),
            )
        )
    }

    @Test
    fun expressionSyntaxFiltersEvaluate() {
        assertTrue(matches(listOf("==", listOf("get", "class"), "street"), mapOf("class" to "street")))
        assertTrue(matches(listOf("!", listOf("has", "class")), emptyMap()))
        assertTrue(
            matches(
                listOf("in", listOf("get", "class"), listOf("literal", listOf("a", "b"))),
                mapOf("class" to "b"),
            )
        )
        assertTrue(matches(listOf("<=", listOf("zoom"), 12.0), zoom = 10.0))
        assertFalse(matches(listOf("<=", listOf("zoom"), 12.0), zoom = 14.0))
    }

    @Test
    fun anAbsentFilterCompilesToNull() {
        assertNull(compile(null))
    }

    // endregion

    @Test
    fun geometryIsOnlyNeededByWithinAndDistance() {
        assertFalse(geometryNeeded(listOf("==", listOf("get", "x"), 1.0)))
        assertTrue(geometryNeeded(listOf("within", mapOf("type" to "Polygon", "coordinates" to emptyList<Any>()))))
        assertTrue(
            geometryNeeded(
                listOf("all", listOf("has", "x"), listOf("distance", mapOf("type" to "Point")))
            )
        )
    }

    @Test
    fun needGeometryIsExposedOnTheCompiledFilter() {
        val plain = compile(listOf("==", listOf("get", "x"), 1.0))
        assertNotNull(plain)
        assertFalse(plain.needGeometry)
    }
}
