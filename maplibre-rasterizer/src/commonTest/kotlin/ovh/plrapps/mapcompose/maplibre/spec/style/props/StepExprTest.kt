package ovh.plrapps.mapcompose.maplibre.spec.style.props

import kotlin.test.Test
import kotlin.test.assertEquals
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue.*

class StepExprTest {
    @Test
    fun testStepEvaluate() {
        val expr = Expr.Step(
            input = Expr.Zoom,
            default = Expr.Constant(0),
            stops = listOf(
                14.0 to Expr.Constant(10),
                15.0 to Expr.Constant(20),
                16.0 to Expr.Constant(30),
                17.0 to Expr.Constant(40)
            )
        )
        // input = 13 -> default
        assertEquals(0, expr.evaluate(null, 13.0))
        // input = 14 -> match1 (10)
        assertEquals(10, expr.evaluate(null, 14.0))
        // input = 15 -> match2 (20)
        assertEquals(20, expr.evaluate(null, 15.0))
        // input = 16 -> match3 (30)
        assertEquals(30, expr.evaluate(null, 16.0))
        // input = 18 -> last
        assertEquals(40, expr.evaluate(null, 18.0))
    }
} 