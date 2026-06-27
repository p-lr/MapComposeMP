package ovh.plrapps.mapcompose.vector.renderer.collision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class LineLabelPlacement {
    companion object {
        fun calculatePlacements(
            points: List<Pair<Float, Float>>,
            textWidth: Float,
            spacing: Float,
            maxAngleDeg: Float = 45f
        ): List<Pair<Pair<Float, Float>, Float>> {
            if (points.size < 2) return emptyList()

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

            val placements = mutableListOf<Pair<Pair<Float, Float>, Float>>()

            for (i in 0 until n - 1) {
                val len = segLen[i]
                if (len == 0f) continue

                val p1 = points[i]
                val dx = points[i + 1].first - p1.first
                val dy = points[i + 1].second - p1.second
                val angle = segAngle[i]

                var localDist = spacing / 2f
                while (localDist + textWidth / 2f <= len) {
                    val globalPos = cumDist[i] + localDist
                    val spanStart = globalPos - textWidth / 2f
                    val spanEnd   = globalPos + textWidth / 2f

                    // Reject if any interior corner within the label span is too sharp.
                    var tooSharp = false
                    for (j in 1 until n - 1) {
                        val cd = cumDist[j]
                        if (cd <= spanStart) continue
                        if (cd >= spanEnd) break
                        var delta = abs(segAngle[j] - segAngle[j - 1])
                        if (delta > 180f) delta = 360f - delta
                        if (delta > maxAngleDeg) { tooSharp = true; break }
                    }

                    if (!tooSharp) {
                        val t = localDist / len
                        placements.add(
                            (p1.first + t * dx to p1.second + t * dy) to angle
                        )
                    }

                    localDist += spacing
                }
            }

            return placements
        }
    }
}