package ovh.plrapps.mapcompose.vector.spec.style.filter

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExpressionResult
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One test per defect found in the audit that motivated the port to MapLibre's engine. Each of
 * these silently produced wrong rendering (or refused to load the style) under the previous
 * hand-rolled implementation.
 */
class FilterRegressionTest {

    private val bare = Json { ignoreUnknownKeys = true }

    private fun matches(filter: Any?, feature: EvalFeature, zoom: Double = 10.0): Boolean {
        val compiled = when (val r = featureFilter(filter, rootKey = "layers[0].filter")) {
            is ExpressionResult.Success -> r.value
            is ExpressionResult.Error -> throw AssertionError("failed to compile: ${r.errors}")
        } ?: return true
        return compiled.filter(GlobalProperties(zoom = zoom), feature)
    }

    /**
     * `["geometry-type"]` used to be mapped to a `get` of the property key `"type"`, while the
     * renderer wrote the geometry type under `"$type"` — so it always compared against null and
     * every such layer rendered nothing. The swisstopo style relies on this in many layers.
     */
    @Test
    fun geometryTypeFilterMatchesTheFeatureGeometry() {
        val filter = listOf("==", listOf("geometry-type"), "LineString")
        assertTrue(matches(filter, EvalFeature(type = "LineString")))
        assertFalse(matches(filter, EvalFeature(type = "Polygon")))
    }

    /** `["!"]` was not a known filter operator and threw, aborting the entire style parse. */
    @Test
    fun notOperatorIsSupported() {
        assertTrue(matches(listOf("!", listOf("has", "x")), EvalFeature(type = "Point")))
        assertFalse(
            matches(
                listOf("!", listOf("has", "x")),
                EvalFeature(type = "Point", properties = mapOf("x" to 1.0)),
            )
        )
    }

    /** The spec form of `in`, with the haystack wrapped in `literal`, used to parse to a null operand. */
    @Test
    fun modernInWithLiteralHaystackMatches() {
        val filter = listOf("in", listOf("get", "c"), listOf("literal", listOf("a", "b")))
        assertTrue(matches(filter, EvalFeature(type = "Point", properties = mapOf("c" to "b"))))
        assertFalse(matches(filter, EvalFeature(type = "Point", properties = mapOf("c" to "z"))))
    }

    /**
     * MVT decodes numbers as Int/Float/Long; the filter engine compared with raw `==` in some
     * branches and with numeric coercion in others, so the same value matched or not depending on
     * which operator was used.
     */
    @Test
    fun numericComparisonsIgnoreTheBoxedNumberType() {
        val eq = listOf("==", listOf("get", "n"), 1.0)
        val inOp = listOf("in", listOf("get", "n"), listOf("literal", listOf(1.0, 2.0)))
        for (value in listOf(1, 1L, 1.0f, 1.0)) {
            val feature = EvalFeature(type = "Point", properties = mapOf("n" to value))
            assertTrue(matches(eq, feature), "== failed for ${value::class.simpleName}")
            assertTrue(matches(inOp, feature), "in failed for ${value::class.simpleName}")
        }
    }

    /** `["zoom"]` outside an interpolate/step used to throw when parsed and be null in filters. */
    @Test
    fun zoomIsUsableDirectlyInAFilter() {
        val filter = listOf("<=", listOf("zoom"), 12.0)
        assertTrue(matches(filter, EvalFeature(type = "Point"), zoom = 10.0))
        assertFalse(matches(filter, EvalFeature(type = "Point"), zoom = 14.0))
    }

    /** `$id` was never populated, so an id filter could never match. */
    @Test
    fun featureIdFiltersMatch() {
        assertTrue(matches(listOf("==", "\$id", 7.0), EvalFeature(type = "Point", id = 7.0)))
        assertFalse(matches(listOf("==", "\$id", 7.0), EvalFeature(type = "Point", id = 8.0)))
    }

    /**
     * An unrecognised operator used to throw out of the serializer, which `getMapLibreConfiguration`
     * turned into a `Result.failure` for the *whole* style. Now it is a diagnostic and the layer
     * simply keeps all of its features.
     */
    @Test
    fun anUnknownOperatorDegradesToNoFilterInsteadOfKillingTheStyle() {
        StyleDiagnostics.drain()
        val layerJson = """
            {"id":"l","type":"line","source":"s","filter":["not-a-real-operator","x",1]}
        """.trimIndent()

        val layer = json.decodeFromString<Layer>(layerJson)
        assertNotNull(layer)
        assertEquals("l", layer.id)
        // Compilation failed, so the layer has no usable filter and shows everything.
        assertEquals(null, layer.filter?.filter)

        val diagnostics = StyleDiagnostics.drain()
        assertTrue(diagnostics.isNotEmpty(), "expected a diagnostic for the unknown operator")
        assertTrue(diagnostics.any { "not-a-real-operator" in it.message })
    }

    /**
     * A data-driven `interpolate` used to cast its input with `as? Double` and returned null for any
     * property the tile encoded as an Int or Float — which is the common case.
     */
    @Test
    fun dataDrivenInterpolateAcceptsNonDoubleProperties() {
        val expr = bare.decodeFromString(
            ExpressionOrValueSerializer(Double.serializer()),
            """["interpolate",["linear"],["get","w"],0,1,10,11]""",
        )
        assertEquals(1.0, expr.processAsDouble(EvalFeature("Point", properties = mapOf("w" to 0))))
        assertEquals(6.0, expr.processAsDouble(EvalFeature("Point", properties = mapOf("w" to 5L))))
        assertEquals(11.0, expr.processAsDouble(EvalFeature("Point", properties = mapOf("w" to 10.0f))))
    }

    /**
     * A legacy `categorical` function used to emit a JSON null default, which then failed to decode
     * for color- and number-valued properties and threw during style parsing.
     */
    @Test
    fun legacyCategoricalFunctionWithoutADefaultParses() {
        StyleDiagnostics.drain()
        val expr = json.decodeFromString<ExpressionOrValue<Color>>(
            """{"property":"kind","type":"categorical","stops":[["a","#ff0000"],["b","#00ff00"]]}"""
        )
        assertEquals(Color(0xFFFF0000), expr.processAsColor(EvalFeature("Polygon", properties = mapOf("kind" to "a"))))
        assertEquals(Color(0xFF00FF00), expr.processAsColor(EvalFeature("Polygon", properties = mapOf("kind" to "b"))))
        assertTrue(StyleDiagnostics.drain().isEmpty())
    }
}
