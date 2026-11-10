package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.data.decodeStyle
import ovh.plrapps.mapcompose.vector.spec.style.expression.captureWarnings
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostic
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transcribed from `maplibre-style-spec/src/expression/visibility.test.ts`.
 *
 * The unit under test is the whole `layout.visibility` path -- `VisibilitySerializer` compiling the
 * property against `visibilitySpec`, then `BaseRenderer.isLayerVisible` evaluating it -- because
 * this port has no `createVisibility` factory to call: a layout property is compiled by its
 * serializer like every other one.
 *
 * Two structural divergences follow from that, and only from that:
 *
 * - **A compile error is recorded, not thrown.** Upstream's `setValue` throws; here the errors go to
 *   `StyleDiagnostics` and surface as `MapLibreConfiguration.diagnostics`, the property becomes
 *   `ExpressionOrValue.Invalid`, and it evaluates to the literal `"visible"` -- which is exactly the
 *   value upstream's own catch block falls back to.
 * - **Global state is declared, not assigned afterwards.** Upstream hands `createVisibility` a live
 *   object and mutates it after the fact, because it also has a runtime `setGlobalStateProperty`.
 *   This port binds a style's `state` defaults at load, so each case declares the value instead.
 */
class VisibilityUpstreamTest {

    @AfterTest
    fun drainDiagnostics() {
        StyleDiagnostics.drain()
    }

    // region create visibility expression

    @Test
    fun `throws Error for invalid function`() {
        val (value, diagnostics) = visibilityWithDiagnostics("""["bla"]""")

        assertTrue(value is ExpressionOrValue.Invalid)
        assertEquals(
            listOf("""Unknown expression "bla". If you wanted a literal array, use ["literal", [...]]."""),
            diagnostics.map { it.message },
        )
        // Upstream throws; here the layer keeps rendering at the spec default.
        assertEquals("visible", value.evaluate())
    }

    // endregion

    // region evaluate visibility expression

    @Test
    fun `literal value none`() {
        val value = visibility(""""none"""")
        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("none", result)
        assertEquals(0, value.globalStateRefs().size)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `literal value visible`() {
        val value = visibility(""""visible"""")
        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertEquals(0, value.globalStateRefs().size)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `global state property set to none`() {
        val value = visibility("""["global-state","x"]""", state = """{"x":{"default":"none"}}""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("none", result)
        assertTrue("x" in value.globalStateRefs())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `global state property set to visible`() {
        val value = visibility("""["global-state","x"]""", state = """{"x":{"default":"visible"}}""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertTrue("x" in value.globalStateRefs())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `global state flag set to false`() {
        val value = visibility(
            """["case",["global-state","x"],"visible","none"]""",
            state = """{"x":{"default":false}}""",
        )

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("none", result)
        assertTrue("x" in value.globalStateRefs())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `global state flag set to true`() {
        val value = visibility(
            """["case",["global-state","x"],"visible","none"]""",
            state = """{"x":{"default":true}}""",
        )

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertTrue("x" in value.globalStateRefs())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `falls back to default for invalid expression with zoom`() {
        val value = visibility("""["case",["==",["zoom"],5],"none","visible"]""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `warns and falls back to default for invalid expression with feature`() {
        val value = visibility("""["get","x"]""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertEquals(1, warnings.size)
        assertEquals(
            """["get","x"]: Expected value to be of type string, but found null instead. """ +
                    "Falling back to visible.",
            warnings[0],
        )
    }

    @Test
    fun `warns and falls back to default for invalid expression with feature state`() {
        val value = visibility("""["feature-state","x"]""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertEquals(1, warnings.size)
        assertEquals(
            """["feature-state","x"]: Expected value to be of type string, but found null instead. """ +
                    "Falling back to visible.",
            warnings[0],
        )
    }

    @Test
    fun `warns and falls back to default for missing global property`() {
        val value = visibility("""["global-state","x"]""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertEquals(1, warnings.size)
        assertEquals(
            """["global-state","x"]: Expected value to be of type string, but found null instead. """ +
                    "Falling back to visible.",
            warnings[0],
        )
    }

    @Test
    fun `warns and falls back to default for invalid global property`() {
        val value = visibility("""["global-state","x"]""", state = """{"x":{"default":"invalid"}}""")

        val (result, warnings) = captureWarnings { value.evaluate() }
        assertEquals("visible", result)
        assertEquals(1, warnings.size)
        assertEquals(
            """["global-state","x"]: Expected value to be one of "visible", "none", """ +
                    """but found "invalid" instead. Falling back to visible.""",
            warnings[0],
        )
    }

    // endregion

    private companion object {
        /**
         * What `BaseRenderer.isLayerVisible` reads: the property evaluated with no zoom and no
         * feature, falling back to the spec default -- upstream's
         * `_literalValue ?? _compiledValue.evaluate({} as GlobalProperties)`.
         */
        fun ExpressionOrValue<String>?.evaluate(): String =
            processAsString() ?: StyleSpecDefaults.VISIBILITY

        fun ExpressionOrValue<String>?.globalStateRefs(): Set<String> =
            (this as? ExpressionOrValue.Expression)?.expression?.globalStateRefs ?: emptySet()

        /**
         * The root key a style property carries here is its own JSON text, not the
         * `layers[0].layout.visibility` path upstream builds -- see `ExpressionOrValueSerializer`,
         * which is handed the property and not its address.
         */
        fun visibility(visibility: String, state: String? = null): ExpressionOrValue<String>? =
            visibilityWithDiagnostics(visibility, state).first

        fun visibilityWithDiagnostics(
            visibility: String,
            state: String? = null,
        ): Pair<ExpressionOrValue<String>?, List<StyleDiagnostic>> {
            StyleDiagnostics.drain()
            val stateBlock = state?.let { """"state":$it,""" }.orEmpty()
            val style = decodeStyle(
                """{"version":8,${stateBlock}"sources":{},"layers":[""" +
                        """{"id":"l","type":"fill","source":"s","layout":{"visibility":$visibility}}]}"""
            )
            return style.layers[0].layout.visibility to StyleDiagnostics.drain()
        }
    }
}
