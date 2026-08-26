package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `line-progress` and `accumulated` are the only two operators in the MapLibre spec with **no
 * fixture directory upstream** — the conformance suite never touches them — yet both are parsed,
 * type-checked and evaluated by `CompoundExpression.definitions`.
 *
 * `heatmap-density` and `elevation` do have a fixture each, but only one apiece and only for the
 * populated case; their defaults and their effect on constant folding are asserted here too.
 */
class CameraOperatorsTest {

    private fun evaluate(source: String, globals: GlobalProperties): Any? {
        val compiled = assertCompiles(createExpression(expr(source), "rk"))
        return compiled.evaluateWithoutErrorHandling(globals, EvalFeature(type = "Point"))
    }

    // region line-progress

    @Test
    fun `line-progress reads the global slot`() {
        assertEquals(0.25, evaluate("""["line-progress"]""", GlobalProperties(zoom = 0.0, lineProgress = 0.25)))
        assertEquals(1.0, evaluate("""["line-progress"]""", GlobalProperties(zoom = 0.0, lineProgress = 1.0)))
    }

    /** The renderer does not populate the slot yet, so an unset value must read as 0, not crash. */
    @Test
    fun `line-progress defaults to zero when unset`() {
        assertEquals(0.0, evaluate("""["line-progress"]""", GlobalProperties(zoom = 0.0)))
    }

    @Test
    fun `line-progress is usable as an interpolate input`() {
        val source = """["interpolate",["linear"],["line-progress"],0,0,1,100]"""
        assertEquals(0.0, evaluate(source, GlobalProperties(zoom = 0.0, lineProgress = 0.0)))
        assertEquals(50.0, evaluate(source, GlobalProperties(zoom = 0.0, lineProgress = 0.5)))
        assertEquals(100.0, evaluate(source, GlobalProperties(zoom = 0.0, lineProgress = 1.0)))
    }

    // endregion

    // region accumulated

    @Test
    fun `accumulated reads the global slot`() {
        assertEquals(7.0, evaluate("""["accumulated"]""", GlobalProperties(zoom = 0.0, accumulated = 7.0)))
        assertEquals("x", evaluate("""["accumulated"]""", GlobalProperties(zoom = 0.0, accumulated = "x")))
    }

    /** Clustering is not implemented, so the slot is always empty; it must read as null. */
    @Test
    fun `accumulated is null when unset`() {
        assertNull(evaluate("""["accumulated"]""", GlobalProperties(zoom = 0.0)))
    }

    // endregion

    // region heatmap-density and elevation

    @Test
    fun `heatmap-density and elevation read their global slots and default to zero`() {
        assertEquals(
            0.5,
            evaluate("""["heatmap-density"]""", GlobalProperties(zoom = 0.0, heatmapDensity = 0.5)),
        )
        assertEquals(0.0, evaluate("""["heatmap-density"]""", GlobalProperties(zoom = 0.0)))

        assertEquals(120.0, evaluate("""["elevation"]""", GlobalProperties(zoom = 0.0, elevation = 120.0)))
        assertEquals(0.0, evaluate("""["elevation"]""", GlobalProperties(zoom = 0.0)))
    }

    // endregion

    /**
     * All four are listed in `GLOBAL_PROPERTY_NAMES`, so they must defeat constant folding — an
     * expression that folded to a literal at parse time would freeze whatever the slot happened to
     * hold when the style was loaded.
     */
    @Test
    fun `camera operators are not constant-folded`() {
        for (operator in listOf("line-progress", "accumulated", "heatmap-density", "elevation")) {
            val compiled = assertCompiles(createExpression(expr("""["$operator"]"""), "rk"))
            assertFalse(
                isExpressionConstant(compiled.expression),
                "[\"$operator\"] must not be treated as constant",
            )
        }
    }

    @Test
    fun `camera operators are feature-constant but not zoom-curve inputs`() {
        for (operator in listOf("line-progress", "accumulated", "heatmap-density", "elevation")) {
            val compiled = assertCompiles(createExpression(expr("""["$operator"]"""), "rk"))
            // They vary per render pass, not per feature.
            assertTrue(isFeatureConstant(compiled.expression), "[\"$operator\"] should be feature-constant")
            // …and none of them is `zoom`, so a zoom-constant check still passes.
            assertTrue(isGlobalPropertyConstant(compiled.expression, listOf("zoom")))
        }
    }
}
