package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.geometry.Offset
import ovh.plrapps.mapcompose.vector.renderer.utils.clipLine
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The label's own path along its line, and the chain of collision circles that follows it.
 *
 * This is the part of `maplibre-gl-js/src/symbol/projection.ts` and
 * `CollisionIndex.placeCollisionCircles` (`src/symbol/collision_index.ts`) a renderer with no camera
 * needs. Upstream walks the line outwards from the anchor by the first and last glyph's offsets
 * (`placeFirstAndLastGlyph`) to get the projected path the label occupies, then spaces circles along
 * it; the walk here is by half the label's width in each direction, which is the same span without
 * the per-glyph bookkeeping a GPU quad buffer needs.
 *
 * Why a chain rather than one box: the straight envelope of a label following a curve claims far
 * more ground than the label covers, so a curved road label used to block its neighbours over an
 * area it never touches. [CollisionDetector] and
 * [ovh.plrapps.mapcompose.vector.symbol.CollisionCircle] have handled a chain since they were
 * written; this is what finally produces one.
 *
 * The pitched-map half of upstream's function -- `projectPathToScreenSpace`, and refusing to place
 * when a vertex falls behind the camera -- is absent for want of a camera, as is
 * `perspectiveRatio`, which is 1 everywhere here.
 */
internal object SymbolProjection {

    /** Upstream's `circleDist`: "Tolerate a slightly longer distance than one diameter". */
    const val CIRCLE_DISTANCE_FACTOR: Float = 2.5f

    /** Upstream's padding at both ends of a segment before circles are interpolated onto it. */
    const val END_PADDING_FACTOR: Float = 0.25f

    /**
     * The stretch of [line] the label covers: [halfLength] of arc length either side of [anchor],
     * with the line's own vertices in between kept so the path bends where the road does.
     *
     * Returns null when the anchor is not on the line at all.
     */
    fun labelPath(
        line: List<Pair<Float, Float>>,
        anchor: Offset,
        halfLength: Float,
    ): List<Offset>? = labelPathOf(line, anchor, halfLength)?.points

    /**
     * As [labelPath], but keeping the anchor's own index in the returned path.
     *
     * The draw pass needs it: a glyph's position is an arc length measured **from the anchor**, and
     * the anchor is not the path's midpoint whenever the walk ran out of line on one side.
     */
    fun labelPathOf(
        line: List<Pair<Float, Float>>,
        anchor: Offset,
        halfLength: Float,
    ): LabelPath? {
        if (line.size < 2) return null

        // The segment the anchor sits on, and how far along it.
        var bestSegment = -1
        var bestT = 0f
        var bestDistanceSq = Float.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val (ax, ay) = line[i]
            val (bx, by) = line[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 0f) 0f else
                (((anchor.x - ax) * dx + (anchor.y - ay) * dy) / lenSq).coerceIn(0f, 1f)
            val px = ax + dx * t
            val py = ay + dy * t
            val distSq = (px - anchor.x) * (px - anchor.x) + (py - anchor.y) * (py - anchor.y)
            if (distSq < bestDistanceSq) {
                bestDistanceSq = distSq
                bestSegment = i
                bestT = t
            }
        }
        if (bestSegment < 0) return null

        val start = Offset(
            line[bestSegment].first + (line[bestSegment + 1].first - line[bestSegment].first) * bestT,
            line[bestSegment].second + (line[bestSegment + 1].second - line[bestSegment].second) * bestT,
        )

        val backwards = walk(line, bestSegment, start, halfLength, forward = false)
        val forwards = walk(line, bestSegment, start, halfLength, forward = true)

        val path = ArrayList<Offset>(backwards.size + forwards.size + 1)
        for (i in backwards.indices.reversed()) path += backwards[i]
        path += start
        path += forwards
        if (path.size < 2) return null
        return LabelPath(path, anchorIndex = backwards.size)
    }

    /** Vertices from [from] outwards along [line] for [distance], the far end included. */
    private fun walk(
        line: List<Pair<Float, Float>>,
        segment: Int,
        from: Offset,
        distance: Float,
        forward: Boolean,
    ): List<Offset> {
        val out = mutableListOf<Offset>()
        var remaining = distance
        var current = from
        var index = segment

        while (remaining > 0f) {
            val nextIndex = if (forward) index + 1 else index
            val next = line.getOrNull(nextIndex)?.let { Offset(it.first, it.second) } ?: break
            val dx = next.x - current.x
            val dy = next.y - current.y
            val length = sqrt(dx * dx + dy * dy)
            if (length <= 0f) {
                index = if (forward) index + 1 else index - 1
                if (index < 0 || index >= line.size) break
                current = next
                continue
            }
            if (length >= remaining) {
                val t = remaining / length
                out += Offset(current.x + dx * t, current.y + dy * t)
                return out
            }
            out += next
            remaining -= length
            current = next
            index = if (forward) index + 1 else index - 1
            if (index < 0 || index >= line.size - if (forward) 1 else 0) break
        }
        return out
    }

    /**
     * The circles covering [path], ported from the segment loop of `placeCollisionCircles`.
     *
     * [clipBounds], when given, is the padded viewport: upstream clips the projected path to it and
     * only builds circles for the visible segments, so a label running far off screen does not fill
     * the index with circles nobody can collide with.
     */
    fun collisionCircles(
        path: List<Offset>,
        radius: Float,
        clipBounds: ClipBounds? = null,
    ): List<CollisionCircle> {
        if (path.size < 2 || radius <= 0f) return emptyList()

        val segments: List<List<Pair<Float, Float>>> = if (clipBounds == null) {
            listOf(path.map { it.x to it.y })
        } else {
            /* Upstream's own quick accept / quick reject before clipping. */
            var minX = path[0].x; var maxX = path[0].x
            var minY = path[0].y; var maxY = path[0].y
            for (p in path) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            when {
                minX >= clipBounds.left && maxX <= clipBounds.right &&
                    minY >= clipBounds.top && maxY <= clipBounds.bottom ->
                    listOf(path.map { it.x to it.y })

                maxX < clipBounds.left || minX > clipBounds.right ||
                    maxY < clipBounds.top || minY > clipBounds.bottom -> emptyList()

                else -> clipLine(
                    listOf(path.map { it.x to it.y }),
                    clipBounds.left, clipBounds.top, clipBounds.right, clipBounds.bottom,
                )
            }
        }

        val circleDistance = radius * CIRCLE_DISTANCE_FACTOR
        val circles = mutableListOf<CollisionCircle>()
        for (segment in segments) {
            if (segment.isEmpty()) continue
            val interpolator = PathInterpolator(segment, radius * END_PADDING_FACTOR)
            val count = if (interpolator.length <= 0.5f * radius) {
                1
            } else {
                ceil(interpolator.paddedLength / circleDistance).toInt() + 1
            }
            for (i in 0 until count) {
                val t = i.toFloat() / maxOf(count - 1, 1).toFloat()
                val (x, y) = interpolator.lerp(t)
                circles += CollisionCircle(x, y, radius)
            }
        }
        return circles
    }

    /**
     * Where each glyph of a label sits along [path], ported from `placeGlyphsAlongLine`.
     *
     * [offsets] are the glyphs' signed arc distances from the label's centre, in [path]'s own
     * units; [anchorDistance] is where that centre sits along the path. [perpendicular] is
     * upstream's `lineOffsetY` -- `text-offset`'s vertical component, applied along the segment
     * normal rather than straight down, because the label is following the line.
     *
     * [flip] walks the label the other way and turns every glyph around, which is what upstream
     * does for `text-keep-upright` when the label would otherwise read right to left.
     *
     * Returns null when the first or last glyph falls off an end of the path -- upstream's
     * `notEnoughRoom`, which it also tests on those two glyphs alone.
     */
    fun placeGlyphsAlongPath(
        path: List<Offset>,
        anchorDistance: Float,
        offsets: FloatArray,
        perpendicular: Float = 0f,
        flip: Boolean = false,
    ): List<PathPoint>? {
        if (path.size < 2 || offsets.isEmpty()) return null
        val interpolator = PathInterpolator(path.map { it.x to it.y })
        val direction = if (flip) -1f else 1f

        val out = ArrayList<PathPoint>(offsets.size)
        for (offset in offsets) {
            val point = interpolator.atDistance(anchorDistance + direction * offset) ?: return null
            out += if (flip) point.turnedAround() else point
        }
        if (perpendicular == 0f) return out
        return out.map { it.movedAlongNormal(perpendicular) }
    }

    /** The padded viewport a circle chain is clipped to. */
    data class ClipBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
}

/**
 * The stretch of line a label covers, and where its anchor sits in it.
 *
 * [points] is [SymbolProjection.labelPath]'s path; `points[anchorIndex]` is the anchor itself.
 */
internal class LabelPath(val points: List<Offset>, val anchorIndex: Int)

/** A point on a path and the direction of the segment it lies on, in degrees. */
internal class PathPoint(val x: Float, val y: Float, val angleDeg: Float) {

    /** The same point, read in the opposite direction -- upstream's flipped glyph. */
    fun turnedAround(): PathPoint = PathPoint(x, y, angleDeg + 180f)

    /**
     * The point moved by [distance] along the segment's normal.
     *
     * The normal is the direction rotated a quarter turn the way the canvas turns, so a positive
     * distance is "below the line" exactly as a positive `text-offset` y is below the anchor.
     */
    fun movedAlongNormal(distance: Float): PathPoint {
        val radians = angleDeg * PI.toFloat() / 180f
        return PathPoint(
            x = x - sin(radians) * distance,
            y = y + cos(radians) * distance,
            angleDeg = angleDeg,
        )
    }
}

/**
 * Constant-speed interpolation along a polyline, ported from
 * `maplibre-gl-js/src/symbol/path_interpolator.ts`.
 */
internal class PathInterpolator(points: List<Pair<Float, Float>>, padding: Float = 0f) {

    private val points: List<Pair<Float, Float>> = points
    private val distances: FloatArray = FloatArray(points.size)

    val length: Float
    val padding: Float
    val paddedLength: Float

    init {
        for (i in 1 until points.size) {
            val dx = points[i].first - points[i - 1].first
            val dy = points[i].second - points[i - 1].second
            distances[i] = distances[i - 1] + sqrt(dx * dx + dy * dy)
        }
        length = distances.lastOrNull() ?: 0f
        this.padding = minOf(padding, length * 0.5f)
        paddedLength = length - this.padding * 2f
    }

    fun lerp(t: Float): Pair<Float, Float> {
        if (points.size == 1) return points[0]
        if (points.isEmpty()) return 0f to 0f

        val clamped = t.coerceIn(0f, 1f)
        val point = at(clamped * paddedLength + padding)
        return point.x to point.y
    }

    /** The cumulative arc length from the path's start to vertex [index]. */
    fun distanceTo(index: Int): Float = distances.getOrElse(index) { 0f }

    /**
     * The point at [distance] along the path -- the *un-padded* path, unlike [lerp] -- together
     * with the direction of the segment it falls on.
     *
     * Returns null outside `0..length`, which is what tells a caller a glyph ran off the end.
     */
    fun atDistance(distance: Float): PathPoint? {
        if (points.size < 2) return null
        if (distance < 0f || distance > length) return null
        return at(distance)
    }

    private fun at(distance: Float): PathPoint {
        var currentIndex = 1
        var distOfCurrentIdx = distances[currentIndex]

        while (distOfCurrentIdx < distance && currentIndex < distances.size - 1) {
            distOfCurrentIdx = distances[++currentIndex]
        }

        val prevIndex = currentIndex - 1
        val distOfPrevIdx = distances[prevIndex]
        val segmentLength = distOfCurrentIdx - distOfPrevIdx
        val segmentT = if (segmentLength > 0f) (distance - distOfPrevIdx) / segmentLength else 0f

        val (prevX, prevY) = points[prevIndex]
        val (currentX, currentY) = points[currentIndex]
        val angle = atan2(currentY - prevY, currentX - prevX) * 180f / PI.toFloat()
        return PathPoint(
            x = prevX * (1f - segmentT) + currentX * segmentT,
            y = prevY * (1f - segmentT) + currentY * segmentT,
            angleDeg = angle,
        )
    }
}
