package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [UnitBezier] is a port of the `@mapbox/unitbezier` package, which ships no test file into this
 * repo. It backs the `cubic-bezier` interpolation curve.
 */
class UnitBezierTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-5) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    /** `cubic-bezier(0, 0, 1, 1)` is the identity curve. */
    @Test
    fun `the identity curve is linear`() {
        val bezier = UnitBezier(0.0, 0.0, 1.0, 1.0)
        for (x in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            assertClose(x, bezier.solve(x))
        }
    }

    @Test
    fun `endpoints are exact`() {
        val bezier = UnitBezier(0.42, 0.0, 0.58, 1.0)
        assertClose(0.0, bezier.solve(0.0))
        assertClose(1.0, bezier.solve(1.0))
    }

    /** `cubic-bezier(0.5, 0, 0.5, 1)` eases in and out symmetrically about the midpoint. */
    @Test
    fun `ease-in-out is symmetric about the midpoint`() {
        val bezier = UnitBezier(0.5, 0.0, 0.5, 1.0)
        assertClose(0.5, bezier.solve(0.5))
        val quarter = bezier.solve(0.25)
        val threeQuarter = bezier.solve(0.75)
        assertTrue(quarter < 0.25, "ease-in should lag linear, got $quarter")
        assertTrue(threeQuarter > 0.75, "ease-out should lead linear, got $threeQuarter")
        assertClose(1.0, quarter + threeQuarter)
    }

    @Test
    fun `ease-in lags and ease-out leads`() {
        val easeIn = UnitBezier(0.42, 0.0, 1.0, 1.0)
        val easeOut = UnitBezier(0.0, 0.0, 0.58, 1.0)
        assertTrue(easeIn.solve(0.5) < 0.5)
        assertTrue(easeOut.solve(0.5) > 0.5)
    }

    @Test
    fun `the curve is monotonically increasing`() {
        val bezier = UnitBezier(0.25, 0.1, 0.25, 1.0)
        var previous = -1.0
        for (step in 0..100) {
            val value = bezier.solve(step / 100.0)
            assertTrue(value >= previous - 1e-9, "not monotonic at $step: $previous then $value")
            previous = value
        }
    }

    /**
     * A curve whose x-derivative vanishes stalls Newton's method; the solver must fall back to
     * bisection rather than diverge.
     */
    @Test
    fun `solveCurveX falls back to bisection when Newton stalls`() {
        val bezier = UnitBezier(0.0, 0.0, 0.0, 1.0)
        for (x in listOf(0.0, 0.3, 0.5, 0.9, 1.0)) {
            val t = bezier.solveCurveX(x)
            assertTrue(t in 0.0..1.0, "t out of range for x=$x: $t")
            assertClose(x, bezier.sampleCurveX(t), tolerance = 1e-4)
        }
    }

    /**
     * Upstream clamps only inside the bisection fallback, so an out-of-range x that Newton solves
     * cleanly comes back out of range too. `Interpolate` never asks for one — its input is the
     * exponential factor, which is already within 0..1 — but the behaviour is asserted here so the
     * port is not "fixed" into diverging.
     */
    @Test
    fun `x outside the unit interval is not clamped by the Newton path`() {
        val bezier = UnitBezier(0.42, 0.0, 0.58, 1.0)
        assertTrue(bezier.solveCurveX(-1.0) < 0.0)
        assertTrue(bezier.solveCurveX(2.0) > 1.0)
    }
}
