package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Fast approximate geodesic distances, valid near a given latitude.
 *
 * Ported from `maplibre-style-spec/src/util/cheap_ruler.ts`, itself a subset of
 * https://github.com/mapbox/cheap-ruler. Distances are in metres.
 */
class CheapRuler(lat: Double) {

    private val kx: Double
    private val ky: Double

    init {
        // Curvature formulas from https://en.wikipedia.org/wiki/Earth_radius#Meridional
        val m = RAD * RE * 1000
        val coslat = cos(lat * RAD)
        val w2 = 1 / (1 - E2 * (1 - coslat * coslat))
        val w = sqrt(w2)

        kx = m * w * coslat          // based on normal radius of curvature
        ky = m * w * w2 * (1 - E2)   // based on meridional radius of curvature
    }

    /** Distance in metres between two `[longitude, latitude]` points. */
    fun distance(a: Coord, b: Coord): Double {
        val dx = wrap(a[0] - b[0]) * kx
        val dy = (a[1] - b[1]) * ky
        return sqrt(dx * dx + dy * dy)
    }

    /** The closest point on [line] to [p], plus its segment index and position along that segment. */
    fun pointOnLine(line: List<Coord>, p: Coord): PointOnLine {
        var minDist = Double.POSITIVE_INFINITY
        var minX = 0.0
        var minY = 0.0
        var minI = 0
        var minT = 0.0

        for (i in 0 until line.size - 1) {
            var x = line[i][0]
            var y = line[i][1]
            var dx = wrap(line[i + 1][0] - x) * kx
            var dy = (line[i + 1][1] - y) * ky
            var t = 0.0

            if (dx != 0.0 || dy != 0.0) {
                t = (wrap(p[0] - x) * kx * dx + (p[1] - y) * ky * dy) / (dx * dx + dy * dy)

                if (t > 1) {
                    x = line[i + 1][0]
                    y = line[i + 1][1]
                } else if (t > 0) {
                    x += (dx / kx) * t
                    y += (dy / ky) * t
                }
            }

            dx = wrap(p[0] - x) * kx
            dy = (p[1] - y) * ky

            val sqDist = dx * dx + dy * dy
            if (sqDist < minDist) {
                minDist = sqDist
                minX = x
                minY = y
                minI = i
                minT = t
            }
        }

        return PointOnLine(doubleArrayOf(minX, minY), minI, minT.coerceIn(0.0, 1.0))
    }

    private fun wrap(degIn: Double): Double {
        var deg = degIn
        while (deg < -180) deg += 360
        while (deg > 180) deg -= 360
        return deg
    }

    data class PointOnLine(val point: Coord, val index: Int, val t: Double)

    private companion object {
        const val RE = 6378.137            // equatorial radius, km
        const val FE = 1 / 298.257223563   // flattening
        const val E2 = FE * (2 - FE)
        const val RAD = PI / 180
    }
}
