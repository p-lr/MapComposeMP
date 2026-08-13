package ovh.plrapps.mapcompose.vector.spec.style.expression

import kotlin.math.abs

/**
 * Cubic bezier easing solver, used by `["interpolate", ["cubic-bezier", x1, y1, x2, y2], …]`.
 *
 * Ported from `@mapbox/unitbezier`, which MapLibre depends on. The control points are `(0,0)`,
 * `(x1,y1)`, `(x2,y2)`, `(1,1)`; [solve] maps an x in 0..1 to the corresponding y.
 */
class UnitBezier(private val p1x: Double, private val p1y: Double, private val p2x: Double, private val p2y: Double) {

    // Pre-calculate the polynomial coefficients.
    private val cx = 3.0 * p1x
    private val bx = 3.0 * (p2x - p1x) - cx
    private val ax = 1.0 - cx - bx

    private val cy = 3.0 * p1y
    private val by = 3.0 * (p2y - p1y) - cy
    private val ay = 1.0 - cy - by

    fun sampleCurveX(t: Double): Double = ((ax * t + bx) * t + cx) * t

    fun sampleCurveY(t: Double): Double = ((ay * t + by) * t + cy) * t

    fun sampleCurveDerivativeX(t: Double): Double = (3.0 * ax * t + 2.0 * bx) * t + cx

    fun solveCurveX(x: Double, epsilon: Double = 1e-6): Double {
        // First try a few iterations of Newton's method -- normally very fast.
        var t2 = x
        for (i in 0 until 8) {
            val x2 = sampleCurveX(t2) - x
            if (abs(x2) < epsilon) return t2
            val d2 = sampleCurveDerivativeX(t2)
            if (abs(d2) < 1e-6) break
            t2 -= x2 / d2
        }

        // Fall back to the bisection method for reliability.
        var t0 = 0.0
        var t1 = 1.0
        t2 = x

        if (t2 < t0) return t0
        if (t2 > t1) return t1

        while (t0 < t1) {
            val x2 = sampleCurveX(t2)
            if (abs(x2 - x) < epsilon) return t2
            if (x > x2) t0 = t2 else t1 = t2
            t2 = (t1 - t0) * 0.5 + t0
        }

        return t2
    }

    fun solve(x: Double, epsilon: Double = 1e-6): Double = sampleCurveY(solveCurveX(x, epsilon))
}
