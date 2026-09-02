package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.hypot

/**
 * `line-dasharray`, ported from maplibre-gl-js `src/render/line_atlas.ts`.
 *
 * Upstream bakes the pattern into a signed-distance texture and samples it per fragment. There is no
 * fragment stage here, so [dashRuns] cuts the polyline into the runs the pattern leaves painted and
 * each run is tessellated like any other line. That is exact for the geometry, composes with blur,
 * offset and gradients -- which a `PathEffect` never did -- and costs nothing per pixel.
 *
 * **Divergence:** upstream's texture is antialiased along the line, so a dash ends with a soft edge;
 * a cut run ends with the layer's `line-cap`, hard by default. The difference is sub-pixel.
 */

/** A dash pattern in canvas pixels: alternating painted and blank lengths, starting painted. */
internal class DashPattern(val intervals: FloatArray, val phase: Float)

/**
 * Converts a `line-dasharray` into a [DashPattern].
 *
 * Spec: "The lengths are later scaled by the line width. To convert a dash length to pixels,
 * multiply the length by the current line width."
 *
 * An odd-length array names a pattern whose first and last parts are both dashes; upstream joins
 * them seamlessly by starting its first range at `-dasharray[last]`, so the period is the plain sum
 * of the array rather than twice it. The same pattern as an even-length list is
 * `[first + last, second, ...]` entered `last` pixels in, which is what [DashPattern.phase] carries.
 *
 * Returns `null` for an array that yields no positive interval, so the caller draws a solid line
 * rather than an invisible one.
 */
internal fun dashPattern(dashArray: List<Double>, lineWidthPx: Float): DashPattern? {
    if (dashArray.size < 2 || lineWidthPx <= 0f) return null
    val scaled = FloatArray(dashArray.size) { (dashArray[it] * lineWidthPx).toFloat() }
    if (scaled.any { it < 0f } || scaled.sum() <= 0f) return null

    if (scaled.size % 2 == 0) return DashPattern(scaled, phase = 0f)

    val last = scaled[scaled.size - 1]
    val joined = FloatArray(scaled.size - 1)
    joined[0] = scaled[0] + last
    for (i in 1 until scaled.size - 1) joined[i] = scaled[i]
    return DashPattern(joined, phase = last)
}

/** One painted run of a dashed line, with how far along the whole polyline it begins. */
internal class DashRun(val points: FloatArray, val startDistance: Float)

/**
 * Cuts a polyline into the runs a dash pattern paints.
 *
 * [points] and each run's points are `x, y` interleaved. A run of fewer than two points -- which a
 * zero-length dash produces -- is dropped rather than returned. [DashRun.startDistance] is what lets
 * a dashed `line-gradient` keep its progress relative to the whole line rather than to each dash.
 */
internal fun dashRuns(points: FloatArray, pattern: DashPattern): List<DashRun> {
    val intervals = pattern.intervals
    val period = intervals.sum()
    if (period <= 0f || points.size < 4) return emptyList()

    var index = 0
    var into = pattern.phase % period
    while (into >= intervals[index]) {
        into -= intervals[index]
        index = (index + 1) % intervals.size
    }
    var remaining = intervals[index] - into
    var painted = index % 2 == 0

    val runs = ArrayList<DashRun>()
    var current = ArrayList<Float>()
    var currentStart = 0f
    var covered = 0f
    if (painted) {
        current.add(points[0])
        current.add(points[1])
    }

    fun advance() {
        index = (index + 1) % intervals.size
        painted = !painted
        remaining = intervals[index]
    }

    fun closeRun() {
        if (current.size >= 4) {
            runs.add(DashRun(FloatArray(current.size) { current[it] }, currentStart))
        }
        current = ArrayList()
    }

    for (i in 1 until points.size / 2) {
        val ax = points[2 * i - 2]
        val ay = points[2 * i - 1]
        val bx = points[2 * i]
        val by = points[2 * i + 1]
        val length = hypot(bx - ax, by - ay)
        if (length <= 0f) continue

        var travelled = 0f
        while (length - travelled > remaining) {
            travelled += remaining
            val t = travelled / length
            val x = ax + (bx - ax) * t
            val y = ay + (by - ay) * t
            val here = covered + travelled
            if (painted) {
                current.add(x)
                current.add(y)
                closeRun()
            } else {
                current.add(x)
                current.add(y)
                currentStart = here
            }
            advance()
            // A zero-length part paints nothing and is stepped straight over.
            var guard = 0
            while (remaining <= 0f && guard++ < intervals.size) {
                if (painted) {
                    closeRun()
                } else {
                    current = arrayListOf(x, y)
                    currentStart = here
                }
                advance()
            }
        }
        remaining -= length - travelled
        covered += length
        if (painted) {
            current.add(bx)
            current.add(by)
        }
    }
    closeRun()

    return runs
}
