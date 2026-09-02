package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tessellates a polyline into the triangle ribbon `line` layers are drawn from.
 *
 * A structural port of maplibre-gl-js `src/data/bucket/line_bucket.ts` -- `addLine`,
 * `addCurrentVertex` and `addHalfVertex` -- with upstream's constants and join ladder kept intact so
 * the two can be diffed. `library/tools/fetch-line-shaders.sh` re-fetches the sources it follows.
 *
 * **The one structural change.** Upstream emits two vertices per line vertex, a left and a right
 * half-vertex, and lets `line.fragment.glsl` compute the alpha falloff across the ribbon from the
 * interpolated `v_normal`. There is no fragment stage here, so a column carries the whole
 * cross-section: one vertex per entry of [alphaRings], each holding the alpha the shader would have
 * produced there. Because the shader interpolates position and `v_normal` linearly over its
 * triangles, sampling that linear function at the ring fractions and interpolating again between
 * them is the same function -- the ribbon is reproduced, not approximated. Two things fall out of
 * the formulation for free that an "offset the polyline by +/- halfWidth" construction gets wrong:
 * a miter stretches the geometry while leaving the alpha ramp at `+/-1`, so the feather is
 * genuinely wider at a sharp corner; and a bevel's or square cap's along-line shift varies linearly
 * across the cross-section.
 *
 * **Divergences:**
 *
 * - A **round cap or round join** is real fan geometry here. Upstream sets `v_normal.x = 1` on those
 *   vertices so its `dist` becomes a Euclidean norm, which Gouraud interpolation cannot reproduce;
 *   [roundStepCount] instead picks an angular step that keeps the chord sag under a quarter pixel.
 *   This is the one new divergence the mesh introduces, and it replaces `line-round-limit is inert`.
 * - `MAX_LINE_DISTANCE`, `LINE_DISTANCE_SCALE` and the distance-reset recursion in upstream's
 *   `addCurrentVertex` are **not** ported. They exist only because `linesofar` is packed into 15
 *   bits of a vertex attribute; progress is a `Float` here, so resetting it would add vertices for
 *   nothing.
 * - `EXTRUDE_SCALE = 63` and the `round(63 * extrude) + 128` byte quantisation are not ported
 *   either: extrudes stay full-precision floats, which is strictly closer to the ideal geometry
 *   than upstream at a large miter.
 * - `lineClips` / `layoutVertexArray2` are geojson-vt clip metadata for `lineMetrics`, which
 *   `GeoJsonSource` does not support, and `subdivideVertexLine` is globe rendering only.
 */

/** Upstream's `COS_HALF_SHARP_CORNER`, `cos(75 / 2 degrees)`. */
internal val COS_HALF_SHARP_CORNER = cos(75.0 / 2.0 * (PI / 180.0)).toFloat()

/**
 * Upstream's `SHARP_CORNER_OFFSET`: how far from a sharp corner the extra vertices go.
 *
 * Upstream states it in pixels of a 512 px tile and converts to tile units; a tile is already in
 * canvas pixels here, so the conversion is `SHARP_CORNER_OFFSET * canvasSize / 512`.
 */
internal const val SHARP_CORNER_OFFSET = 15f

/** Upstream's `DEG_PER_TRIANGLE`, the angular resolution of a fake-round join. */
internal const val DEG_PER_TRIANGLE = 20f

/**
 * The most vertices one [LineMesh] may hold.
 *
 * `androidx.compose.ui.graphics.Vertices` stores its index list in a `ShortArray` and validates only
 * that indices are in range, so anything past this truncates silently and draws garbage. Upstream
 * has the same shape of limit in `SegmentVector.MAX_VERTEX_ARRAY_LENGTH`, one bit larger because its
 * indices are unsigned.
 */
internal const val MAX_VERTEX_ARRAY_LENGTH = 32767

/** One chunk of tessellated ribbon, in canvas pixels. */
internal class LineMesh(
    val positions: FloatArray,
    /** The fragment shader's `alpha` at each vertex. */
    val alphas: FloatArray,
    /** `line-progress` in `0..1` at each vertex, for `line-gradient`. */
    val progress: FloatArray,
    val indices: IntArray,
    val vertexCount: Int,
    val indexCount: Int,
)

/**
 * Tessellates one polyline.
 *
 * [points] is `x, y` interleaved in canvas pixels. [totalLength] is the length the returned
 * progress values are relative to, which is the whole feature's rather than this polyline's when a
 * feature has several. Returns one mesh per [MAX_VERTEX_ARRAY_LENGTH] chunk.
 */
internal fun tessellateLine(
    points: FloatArray,
    pointCount: Int,
    isPolygon: Boolean,
    join: String,
    cap: String,
    miterLimit: Float,
    roundLimit: Float,
    outset: Float,
    inset: Float,
    blur2: Float,
    offset: Float = 0f,
    sharpCornerOffset: Float = SHARP_CORNER_OFFSET,
    startDistance: Float = 0f,
    totalLength: Float = 0f,
): List<LineMesh> {
    val builder = LineMeshBuilder(outset, inset, blur2, offset, startDistance, totalLength)
    builder.addLine(points, pointCount, isPolygon, join, cap, miterLimit, roundLimit, sharpCornerOffset)
    return builder.finish()
}

/**
 * How many angular steps a round cap or join of [radius] pixels needs.
 *
 * The chord of one step sags `radius * (1 - cos(step / 2))` from the true arc; solving that for a
 * quarter pixel keeps a cap indistinguishable from upstream's per-fragment one. Upstream's
 * [DEG_PER_TRIANGLE] is the equivalent constant for a fake-round join, where the angle swept is
 * small and the resolution can be fixed.
 */
internal fun roundStepCount(radius: Float, sag: Float = 0.25f, sweep: Float = PI.toFloat()): Int {
    if (radius <= sag) return 2
    val step = 2f * acos(1f - sag / radius)
    return ceil(sweep / step).toInt().coerceIn(2, 64)
}

private class LineMeshBuilder(
    private val outset: Float,
    private val inset: Float,
    private val blur2: Float,
    private val offset: Float,
    private val startDistance: Float,
    private val totalLength: Float,
) {
    private val rings = alphaRings(outset, inset, blur2)
    private val ringAlphas = FloatArray(rings.size) { alphaAt(abs(rings[it])) }
    /** The positive half of [rings], for the radial spokes of a round cap. */
    private val spokes = rings.filter { it >= 0f }.toFloatArray()
    private val spokeAlphas = FloatArray(spokes.size) { alphaAt(spokes[it]) }

    /**
     * The shader's alpha at a ring, snapped to zero when it rounds to nothing.
     *
     * A ring sits exactly on a breakpoint, so the alpha there is 0 or 1 up to float error; snapping
     * is what lets [stitch] recognise the transparent core of a gapped line and drop it.
     */
    private fun alphaAt(ring: Float): Float {
        val alpha = lineAlpha(ring * outset, outset, inset, blur2)
        return if (alpha < ALPHA_EPSILON) 0f else alpha
    }

    private val meshes = ArrayList<LineMesh>()
    private var positions = FloatArray(512)
    private var alphas = FloatArray(256)
    private var progress = FloatArray(256)
    private var indices = IntArray(768)
    private var vertexCount = 0
    private var indexCount = 0

    /** Vertex index the current strip's previous column starts at, or -1 between strips. */
    private var previousColumn = -1
    private var columnWidth = 0

    private var distance = 0f

    // The last column's extrudes, so a fake-round pie slice can hold one side still.
    private var lastLeftX = 0f
    private var lastLeftY = 0f
    private var lastRightX = 0f
    private var lastRightY = 0f

    fun finish(): List<LineMesh> {
        flush()
        return meshes
    }

    fun addLine(
        points: FloatArray,
        pointCount: Int,
        isPolygon: Boolean,
        join: String,
        cap: String,
        miterLimit: Float,
        roundLimit: Float,
        sharpCornerOffset: Float,
    ) {
        distance = startDistance

        // Trim duplicate vertices off both ends, as upstream does.
        var len = pointCount
        while (len >= 2 && samePoint(points, len - 1, len - 2)) len--
        var first = 0
        while (first < len - 1 && samePoint(points, first, first + 1)) first++
        if (len - first < (if (isPolygon) 3 else 2)) return

        val effectiveMiterLimit = if (join == "bevel") 1.05f else miterLimit

        breakStrip()

        var hasCurrent = false
        var currentX = 0f
        var currentY = 0f
        var hasPrev = false
        var prevX = 0f
        var prevY = 0f
        var hasPrevNormal = false
        var prevNormalX = 0f
        var prevNormalY = 0f
        var nextNormalX = 0f
        var nextNormalY = 0f
        var hasNextNormal = false

        if (isPolygon) {
            currentX = points[2 * (len - 2)]
            currentY = points[2 * (len - 2) + 1]
            hasCurrent = true
            val n = unitPerp(points[2 * first] - currentX, points[2 * first + 1] - currentY)
            nextNormalX = n.first
            nextNormalY = n.second
            hasNextNormal = true
        }

        for (i in first until len) {
            val hasNext: Boolean
            var nextX = 0f
            var nextY = 0f
            if (i == len - 1) {
                hasNext = isPolygon
                if (isPolygon) {
                    nextX = points[2 * (first + 1)]
                    nextY = points[2 * (first + 1) + 1]
                }
            } else {
                hasNext = true
                nextX = points[2 * (i + 1)]
                nextY = points[2 * (i + 1) + 1]
            }

            // If two consecutive vertices are the same, skip the current one.
            if (hasNext && points[2 * i] == nextX && points[2 * i + 1] == nextY) continue

            if (hasNextNormal) {
                prevNormalX = nextNormalX
                prevNormalY = nextNormalY
                hasPrevNormal = true
            }
            if (hasCurrent) {
                prevX = currentX
                prevY = currentY
                hasPrev = true
            }

            currentX = points[2 * i]
            currentY = points[2 * i + 1]
            hasCurrent = true

            if (hasNext) {
                val n = unitPerp(nextX - currentX, nextY - currentY)
                nextNormalX = n.first
                nextNormalY = n.second
            } else {
                nextNormalX = prevNormalX
                nextNormalY = prevNormalY
            }
            hasNextNormal = true
            if (!hasPrevNormal) {
                prevNormalX = nextNormalX
                prevNormalY = nextNormalY
                hasPrevNormal = true
            }

            // The join normal is the angle bisector. At a 180 degree turn the two normals cancel,
            // and upstream keeps the bisector at (0, 0) so that miterLength becomes infinite.
            var joinNormalX = prevNormalX + nextNormalX
            var joinNormalY = prevNormalY + nextNormalY
            if (joinNormalX != 0f || joinNormalY != 0f) {
                val length = hypot(joinNormalX, joinNormalY)
                joinNormalX /= length
                joinNormalY /= length
            }

            val cosAngle = prevNormalX * nextNormalX + prevNormalY * nextNormalY
            val cosHalfAngle = joinNormalX * nextNormalX + joinNormalY * nextNormalY
            val miterLength = if (cosHalfAngle != 0f) 1f / cosHalfAngle else Float.POSITIVE_INFINITY
            val approxAngle = 2f * sqrt(max(0f, 2f - 2f * cosHalfAngle))
            val isSharpCorner = cosHalfAngle < COS_HALF_SHARP_CORNER && hasPrev && hasNext
            val lineTurnsLeft = prevNormalX * nextNormalY - prevNormalY * nextNormalX > 0f

            if (isSharpCorner && i > first) {
                val prevSegmentLength = hypot(currentX - prevX, currentY - prevY)
                if (prevSegmentLength > 2f * sharpCornerOffset) {
                    val t = sharpCornerOffset / prevSegmentLength
                    val newPrevX = currentX - (currentX - prevX) * t
                    val newPrevY = currentY - (currentY - prevY) * t
                    advance(prevX, prevY, newPrevX, newPrevY)
                    column(newPrevX, newPrevY, prevNormalX, prevNormalY, 0f, 0f)
                    prevX = newPrevX
                    prevY = newPrevY
                }
            }

            val middleVertex = hasPrev && hasNext
            var currentJoin = if (middleVertex) join else if (isPolygon) "butt" else cap

            if (middleVertex && currentJoin == "round") {
                if (miterLength < roundLimit) {
                    currentJoin = "miter"
                } else if (miterLength <= 2f) {
                    currentJoin = "fakeround"
                }
            }
            if (currentJoin == "miter" && miterLength > effectiveMiterLimit) {
                currentJoin = "bevel"
            }
            if (currentJoin == "bevel") {
                // The maximum extrude length is twice the line width, so a longer miter needs a
                // different kind of bevel; a very short one is not worth a triangle.
                if (miterLength > 2f) currentJoin = "flipbevel"
                if (miterLength < effectiveMiterLimit) currentJoin = "miter"
            }

            if (hasPrev) advance(prevX, prevY, currentX, currentY)

            when (currentJoin) {
                "miter" -> column(
                    currentX, currentY,
                    joinNormalX * miterLength, joinNormalY * miterLength, 0f, 0f,
                )

                "flipbevel" -> {
                    var bevelX: Float
                    var bevelY: Float
                    if (miterLength > 100f) {
                        // Almost parallel lines.
                        bevelX = -nextNormalX
                        bevelY = -nextNormalY
                    } else {
                        val bevelLength = miterLength *
                            hypot(prevNormalX + nextNormalX, prevNormalY + nextNormalY) /
                            hypot(prevNormalX - nextNormalX, prevNormalY - nextNormalY)
                        val sign = if (lineTurnsLeft) -1f else 1f
                        bevelX = joinNormalY * bevelLength * sign
                        bevelY = -joinNormalX * bevelLength * sign
                    }
                    column(currentX, currentY, bevelX, bevelY, 0f, 0f)
                    column(currentX, currentY, -bevelX, -bevelY, 0f, 0f)
                }

                "bevel", "fakeround" -> {
                    val bevelOffset = -sqrt(max(0f, miterLength * miterLength - 1f))
                    val offsetA = if (lineTurnsLeft) bevelOffset else 0f
                    val offsetB = if (lineTurnsLeft) 0f else bevelOffset

                    if (hasPrev) column(currentX, currentY, prevNormalX, prevNormalY, offsetA, offsetB)

                    if (currentJoin == "fakeround") {
                        // Bevel joins fill the gap between segments with a single pie slice; a round
                        // join is several. Upstream's approximate geometric slerp keeps the slices
                        // evenly spaced along the arc.
                        val n = ((approxAngle * 180f / PI.toFloat()) / DEG_PER_TRIANGLE).roundToInt()
                        for (m in 1 until n) {
                            var t = m.toFloat() / n
                            if (t != 0.5f) {
                                val t2 = t - 0.5f
                                val a = 1.0904f + cosAngle * (-3.2452f + cosAngle * (3.55645f - cosAngle * 1.43519f))
                                val b = 0.848013f + cosAngle * (-1.06021f + cosAngle * 0.215638f)
                                t += t * t2 * (t - 1f) * (a * t2 * t2 + b)
                            }
                            var ex = prevNormalX + (nextNormalX - prevNormalX) * t
                            var ey = prevNormalY + (nextNormalY - prevNormalY) * t
                            val length = hypot(ex, ey)
                            if (length > 0f) {
                                ex /= length
                                ey /= length
                            }
                            if (lineTurnsLeft) {
                                ex = -ex
                                ey = -ey
                            }
                            pieSlice(currentX, currentY, ex, ey, lineTurnsLeft)
                        }
                    }

                    if (hasNext) column(currentX, currentY, nextNormalX, nextNormalY, -offsetA, -offsetB)
                }

                "butt" -> column(currentX, currentY, joinNormalX, joinNormalY, 0f, 0f)

                "square" -> {
                    // Closing or starting the cap.
                    val end = if (hasPrev) 1f else -1f
                    column(currentX, currentY, joinNormalX, joinNormalY, end, end)
                }

                "round" -> {
                    if (hasPrev) {
                        column(currentX, currentY, prevNormalX, prevNormalY, 0f, 0f)
                        roundPatch(currentX, currentY, prevNormalX, prevNormalY, clockwise = true)
                    }
                    if (hasNext) {
                        roundPatch(currentX, currentY, nextNormalX, nextNormalY, clockwise = false)
                        breakStrip()
                        column(currentX, currentY, nextNormalX, nextNormalY, 0f, 0f)
                    }
                }
            }

            if (isSharpCorner && i < len - 1) {
                val nextSegmentLength = hypot(currentX - nextX, currentY - nextY)
                if (nextSegmentLength > 2f * sharpCornerOffset) {
                    val t = sharpCornerOffset / nextSegmentLength
                    val newCurrentX = currentX + (nextX - currentX) * t
                    val newCurrentY = currentY + (nextY - currentY) * t
                    advance(currentX, currentY, newCurrentX, newCurrentY)
                    column(newCurrentX, newCurrentY, nextNormalX, nextNormalY, 0f, 0f)
                    currentX = newCurrentX
                    currentY = newCurrentY
                }
            }
        }

        breakStrip()
    }

    private fun samePoint(points: FloatArray, a: Int, b: Int): Boolean =
        points[2 * a] == points[2 * b] && points[2 * a + 1] == points[2 * b + 1]

    private fun unitPerp(dx: Float, dy: Float): Pair<Float, Float> {
        val length = hypot(dx, dy)
        return if (length > 0f) Pair(-dy / length, dx / length) else Pair(0f, 0f)
    }

    private fun advance(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        distance += hypot(toX - fromX, toY - fromY)
    }

    private fun progressNow(): Float = if (totalLength > 0f) (distance / totalLength).coerceIn(0f, 1f) else 0f

    /**
     * Emits one cross-section, upstream's `addCurrentVertex`.
     *
     * [endLeft] and [endRight] shift a side along the line, which is how upstream builds square caps
     * and the two halves of a bevel.
     */
    private fun column(
        px: Float,
        py: Float,
        normalX: Float,
        normalY: Float,
        endLeft: Float,
        endRight: Float,
    ) {
        val leftX = normalX + normalY * endLeft
        val leftY = normalY - normalX * endLeft
        val rightX = -normalX + normalY * endRight
        val rightY = -normalY - normalX * endRight
        emitColumn(px, py, leftX, leftY, rightX, rightY)
    }

    /**
     * One fake-round pie slice, upstream's lone `addHalfVertex`.
     *
     * Only the outer side of the cross-section moves; the inner side stays where the bevel put it,
     * which is what makes the slices fan around a fixed apex.
     */
    private fun pieSlice(px: Float, py: Float, extrudeX: Float, extrudeY: Float, outerIsRight: Boolean) {
        if (outerIsRight) {
            emitColumn(px, py, lastLeftX, lastLeftY, extrudeX, extrudeY)
        } else {
            emitColumn(px, py, extrudeX, extrudeY, lastRightX, lastRightY)
        }
    }

    /**
     * A round cap or the round half of a round join: a half-disc of radial spokes around [px], [py].
     *
     * Swept from `+normal` through the direction of travel to `-normal`, which is the half-plane
     * outside the segment that ends here. Its own strip, because a spoke has fewer vertices than a
     * cross-section.
     */
    private fun roundPatch(px: Float, py: Float, normalX: Float, normalY: Float, clockwise: Boolean) {
        breakStrip()
        val steps = roundStepCount(outset)
        val progress = progressNow()
        for (step in 0..steps) {
            val angle = PI.toFloat() * step / steps * (if (clockwise) -1f else 1f)
            val c = cos(angle)
            val s = kotlin.math.sin(angle)
            val ex = normalX * c - normalY * s
            val ey = normalX * s + normalY * c
            emitSpoke(px, py, ex, ey, progress)
        }
        breakStrip()
    }

    private fun emitColumn(
        px: Float,
        py: Float,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    ) {
        lastLeftX = leftX
        lastLeftY = leftY
        lastRightX = rightX
        lastRightY = rightY

        // line-offset displaces the whole cross-section along the join normal. Upstream applies
        // `offset * a_extrude * normal.y` per half-vertex, and a_extrude already carries the miter
        // length -- which is the `1 / cos(half angle)` compensation. Half the difference of the two
        // extrudes is that same vector, and using it once per column keeps the cross-section rigid.
        val shiftX = offset * (leftX - rightX) / 2f
        val shiftY = offset * (leftY - rightY) / 2f

        val progressValue = progressNow()
        ensureRoom(rings.size)
        val start = vertexCount
        for (i in rings.indices) {
            val t = (rings[i] + 1f) / 2f
            val ex = rightX + (leftX - rightX) * t
            val ey = rightY + (leftY - rightY) * t
            putVertex(px + shiftX + ex * outset, py + shiftY + ey * outset, ringAlphas[i], progressValue)
        }
        stitch(start, rings.size)
    }

    private fun emitSpoke(px: Float, py: Float, extrudeX: Float, extrudeY: Float, progressValue: Float) {
        val shiftX = offset * extrudeX
        val shiftY = offset * extrudeY
        ensureRoom(spokes.size)
        val start = vertexCount
        for (i in spokes.indices) {
            val r = spokes[i] * outset
            putVertex(px + shiftX + extrudeX * r, py + shiftY + extrudeY * r, spokeAlphas[i], progressValue)
        }
        stitch(start, spokes.size)
    }

    private fun stitch(start: Int, width: Int) {
        if (previousColumn >= 0 && columnWidth == width) {
            for (i in 0 until width - 1) {
                // Skip a band that is transparent at both ends -- the interior of a gapped line.
                val alphaLow = alphas[start + i]
                val alphaHigh = alphas[start + i + 1]
                if (alphaLow == 0f && alphaHigh == 0f) continue
                putTriangle(previousColumn + i, previousColumn + i + 1, start + i)
                putTriangle(previousColumn + i + 1, start + i + 1, start + i)
            }
        }
        previousColumn = start
        columnWidth = width
    }

    private fun breakStrip() {
        previousColumn = -1
        columnWidth = 0
    }

    /** Starts a new chunk before the vertex budget of a `Vertices` index list would be exceeded. */
    private fun ensureRoom(width: Int) {
        if (vertexCount + width <= MAX_VERTEX_ARRAY_LENGTH) return
        val carryStart = previousColumn
        val carryWidth = columnWidth
        val carried = if (carryStart >= 0) FloatArray(carryWidth * 4) else FloatArray(0)
        for (i in 0 until (if (carryStart >= 0) carryWidth else 0)) {
            carried[4 * i] = positions[2 * (carryStart + i)]
            carried[4 * i + 1] = positions[2 * (carryStart + i) + 1]
            carried[4 * i + 2] = alphas[carryStart + i]
            carried[4 * i + 3] = progress[carryStart + i]
        }
        flush()
        if (carryStart >= 0) {
            val start = vertexCount
            for (i in 0 until carryWidth) {
                putVertex(carried[4 * i], carried[4 * i + 1], carried[4 * i + 2], carried[4 * i + 3])
            }
            previousColumn = start
            columnWidth = carryWidth
        }
    }

    private fun flush() {
        if (vertexCount > 0 && indexCount > 0) {
            meshes.add(
                LineMesh(
                    positions = positions,
                    alphas = alphas,
                    progress = progress,
                    indices = indices,
                    vertexCount = vertexCount,
                    indexCount = indexCount,
                )
            )
            positions = FloatArray(512)
            alphas = FloatArray(256)
            progress = FloatArray(256)
            indices = IntArray(768)
        }
        vertexCount = 0
        indexCount = 0
        breakStrip()
    }

    private fun putVertex(x: Float, y: Float, alpha: Float, progressValue: Float) {
        if (2 * vertexCount + 2 > positions.size) positions = positions.copyOf(positions.size * 2)
        if (vertexCount + 1 > alphas.size) {
            alphas = alphas.copyOf(alphas.size * 2)
            progress = progress.copyOf(progress.size * 2)
        }
        positions[2 * vertexCount] = x
        positions[2 * vertexCount + 1] = y
        alphas[vertexCount] = alpha
        progress[vertexCount] = progressValue
        vertexCount++
    }

    private companion object {
        /** An alpha below this rounds to nothing in an 8-bit channel. */
        const val ALPHA_EPSILON = 1f / 512f
    }

    private fun putTriangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount] = a
        indices[indexCount + 1] = b
        indices[indexCount + 2] = c
        indexCount += 3
    }
}
