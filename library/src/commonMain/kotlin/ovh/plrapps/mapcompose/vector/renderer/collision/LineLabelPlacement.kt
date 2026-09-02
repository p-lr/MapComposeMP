package ovh.plrapps.mapcompose.vector.renderer.collision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where labels sit along a line, for `symbol-placement: line` and `line-center`.
 *
 * A placement is a point on the line and the angle of the segment it lands on, in degrees.
 *
 * [calculatePlacements] is a transcription of upstream's `getAnchors` / `resample`
 * (`symbol/get_anchors.ts`); the comments quote it, and the rules it applies -- the spacing
 * enlargement, the first-anchor offset, the in-tile test and the "does the whole label fit on the
 * line" test -- are what keep a road from being labelled once per fragment.
 *
 * The one deliberate departure is the angle convention: upstream's `Anchor` carries `b.angleTo(a)`,
 * i.e. the segment reversed, because its shaper walks the label backwards from the anchor. Here the
 * angle is the segment's own direction, which is what the painter rotates the label by.
 */
class LineLabelPlacement {
    companion object {

        /**
         * Label positions along the line, spaced by [spacing] and none of them wider than the line
         * can carry.
         *
         * The walk is over the line's *cumulative* arc length, not over each segment separately:
         * restarting the phase at every vertex, as this used to, bunched labels up wherever a road
         * was finely subdivided and left long straight runs with a single label in the middle.
         *
         * A position whose label span crosses a corner sharper than [maxAngleDeg] is rejected --
         * upstream's `text-max-angle`, which keeps a name from bending round a hairpin.
         *
         * [tileExtent] is the tile's own size in the same units as [points]; an anchor outside it
         * belongs to the neighbouring tile, which will place it itself. [fontSize] only feeds
         * upstream's fixed extra offset on the first anchor of a line that starts inside the tile,
         * *"to avoid collisions at T intersections"*.
         */
        fun calculatePlacements(
            points: List<Pair<Float, Float>>,
            textWidth: Float,
            spacing: Float,
            maxAngleDeg: Float = 45f,
            tileExtent: Float = Float.MAX_VALUE,
            fontSize: Float = 0f,
        ): List<Pair<Pair<Float, Float>, Float>> {
            if (points.size < 2 || spacing <= 0f) return emptyList()

            // Is the line continued from outside the tile boundary?
            val isLineContinued = points[0].first == 0f || points[0].first == tileExtent ||
                points[0].second == 0f || points[0].second == tileExtent

            /* Is the label long, relative to the spacing? If so, adjust the spacing so there is
             * always a minimum space of `spacing / 4` between label edges. */
            val effectiveSpacing =
                if (spacing - textWidth < spacing / 4f) textWidth + spacing / 4f else spacing

            /* Offset the first anchor by either half the label length plus a fixed extra offset if
             * the line is not continued, or half the spacing if it is. The fixed offset is
             * upstream's `glyphSize * 2` in glyph units, which is two font sizes in pixels. */
            val offset = if (!isLineContinued) {
                ((textWidth / 2f + fontSize * 2f) % effectiveSpacing)
            } else {
                (effectiveSpacing / 2f) % effectiveSpacing
            }

            return resample(
                points = points,
                offset = offset,
                spacing = effectiveSpacing,
                maxAngleDeg = maxAngleDeg,
                labelLength = textWidth,
                isLineContinued = isLineContinued,
                placeAtMiddle = false,
                tileExtent = tileExtent,
            )
        }

        private fun resample(
            points: List<Pair<Float, Float>>,
            offset: Float,
            spacing: Float,
            maxAngleDeg: Float,
            labelLength: Float,
            isLineContinued: Boolean,
            placeAtMiddle: Boolean,
            tileExtent: Float,
        ): List<Pair<Pair<Float, Float>, Float>> {
            val n = points.size
            val halfLabelLength = labelLength / 2f

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

            val lineLength = cumDist[n - 1]
            if (lineLength <= 0f) return emptyList()

            val placements = mutableListOf<Pair<Pair<Float, Float>, Float>>()

            var markedDistance = offset - spacing
            var segment = 0

            while (true) {
                markedDistance += spacing
                if (markedDistance >= lineLength) break

                // Advance to the segment this distance falls in.
                while (segment < n - 2 && cumDist[segment + 1] <= markedDistance) segment++
                val len = segLen[segment]
                if (len <= 0f) continue

                val point = pointOn(points, segment, (markedDistance - cumDist[segment]) / len)

                /* Check that the point is within the tile boundaries and that the label would fit
                 * before the beginning and end of the line if placed at this point. */
                if (point.first < 0f || point.first >= tileExtent ||
                    point.second < 0f || point.second >= tileExtent
                ) continue
                if (markedDistance - halfLabelLength < 0f ||
                    markedDistance + halfLabelLength > lineLength
                ) continue

                if (!spanIsTooSharp(markedDistance, labelLength, cumDist, segAngle, maxAngleDeg, n)) {
                    placements += point to segAngle[segment]
                }
            }

            if (!placeAtMiddle && placements.isEmpty() && !isLineContinued) {
                /* The first attempt at finding anchors at which labels can be placed failed. Try
                 * again, but this time just try placing one anchor at the middle of the line. Every
                 * fit, bounds and angle test above still applies, so a line that genuinely cannot
                 * carry its name still gets nothing. */
                return resample(
                    points = points,
                    offset = lineLength / 2f,
                    spacing = spacing,
                    maxAngleDeg = maxAngleDeg,
                    labelLength = labelLength,
                    isLineContinued = isLineContinued,
                    placeAtMiddle = true,
                    tileExtent = tileExtent,
                )
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
