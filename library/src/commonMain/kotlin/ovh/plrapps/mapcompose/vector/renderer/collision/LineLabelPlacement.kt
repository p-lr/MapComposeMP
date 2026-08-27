package ovh.plrapps.mapcompose.vector.renderer.collision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where labels sit along a line, for `symbol-placement: line` and `line-center`.
 *
 * A placement is a point on the line and the angle of the segment it lands on, in degrees.
 */
class LineLabelPlacement {
    companion object {

        /**
         * Label positions every [spacing] pixels along the line.
         *
         * The walk is over the line's *cumulative* arc length, not over each segment separately:
         * restarting the phase at every vertex, as this used to, bunched labels up wherever a road
         * was finely subdivided and left long straight runs with a single label in the middle.
         *
         * A position whose label span crosses a corner sharper than [maxAngleDeg] is rejected --
         * upstream's `text-max-angle`, which keeps a name from bending round a hairpin.
         */
        fun calculatePlacements(
            points: List<Pair<Float, Float>>,
            textWidth: Float,
            spacing: Float,
            maxAngleDeg: Float = 45f
        ): List<Pair<Pair<Float, Float>, Float>> {
            if (points.size < 2 || spacing <= 0f) return emptyList()

            val n = points.size

            // Precompute per-segment lengths, angles, and cumulative distance at each point.
            val segLen = FloatArray(n - 1)
            val segAngle = FloatArray(n - 1)
            val cumDist = FloatArray(n)
            cumDist[0] = 0f

            for (i in 0 until n - 1) {
                val dx = points[i + 1].first - points[i].first
                val dy = points[i + 1].second - points[i].second
                val len = sqrt(dx * dx + dy * dy)
                segLen[i] = len
                segAngle[i] = if (len > 0f) atan2(dy, dx) * 180f / PI.toFloat()
                              else if (i > 0) segAngle[i - 1] else 0f
                cumDist[i + 1] = cumDist[i] + len
            }

            val total = cumDist[n - 1]
            if (total <= 0f) return emptyList()

            val placements = mutableListOf<Pair<Pair<Float, Float>, Float>>()

            var distance = spacing / 2f
            var segment = 0
            while (distance + textWidth / 2f <= total) {
                // Advance to the segment this distance falls in.
                while (segment < n - 2 && cumDist[segment + 1] <= distance) segment++
                val len = segLen[segment]
                if (len <= 0f) {
                    distance += spacing
                    continue
                }

                if (!spanIsTooSharp(distance, textWidth, cumDist, segAngle, maxAngleDeg, n)) {
                    placements += pointOn(points, segment, (distance - cumDist[segment]) / len) to
                        segAngle[segment]
                }
                distance += spacing
            }

            return placements
        }

        /**
         * The single placement at the line's midpoint, for `symbol-placement: line-center`.
         *
         * Upstream picks the anchor nearest the middle of the line's length rather than the middle
         * vertex, so an unevenly subdivided road still gets its label in the geometric middle.
         */
        fun centerPlacement(points: List<Pair<Float, Float>>): Pair<Pair<Float, Float>, Float>? {
            if (points.size < 2) return null
            val n = points.size
            val segLen = FloatArray(n - 1)
            var total = 0f
            for (i in 0 until n - 1) {
                val dx = points[i + 1].first - points[i].first
                val dy = points[i + 1].second - points[i].second
                segLen[i] = sqrt(dx * dx + dy * dy)
                total += segLen[i]
            }
            if (total <= 0f) return null

            val half = total / 2f
            var travelled = 0f
            for (i in 0 until n - 1) {
                if (travelled + segLen[i] >= half && segLen[i] > 0f) {
                    val t = (half - travelled) / segLen[i]
                    val dx = points[i + 1].first - points[i].first
                    val dy = points[i + 1].second - points[i].second
                    val angle = atan2(dy, dx) * 180f / PI.toFloat()
                    return pointOn(points, i, t) to angle
                }
                travelled += segLen[i]
            }
            return null
        }

        private fun pointOn(
            points: List<Pair<Float, Float>>,
            segment: Int,
            t: Float,
        ): Pair<Float, Float> {
            val from = points[segment]
            val to = points[segment + 1]
            return from.first + t * (to.first - from.first) to from.second + t * (to.second - from.second)
        }

        /** Whether any interior corner inside the label's span turns more than [maxAngleDeg]. */
        private fun spanIsTooSharp(
            center: Float,
            textWidth: Float,
            cumDist: FloatArray,
            segAngle: FloatArray,
            maxAngleDeg: Float,
            n: Int,
        ): Boolean {
            val spanStart = center - textWidth / 2f
            val spanEnd = center + textWidth / 2f
            for (j in 1 until n - 1) {
                val cd = cumDist[j]
                if (cd <= spanStart) continue
                if (cd >= spanEnd) break
                var delta = abs(segAngle[j] - segAngle[j - 1])
                if (delta > 180f) delta = 360f - delta
                if (delta > maxAngleDeg) return true
            }
            return false
        }
    }
}
