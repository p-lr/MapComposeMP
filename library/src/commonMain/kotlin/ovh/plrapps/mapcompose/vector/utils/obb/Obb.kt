package ovh.plrapps.mapcompose.vector.utils.obb

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import ovh.plrapps.mapcompose.vector.utils.rtree.AABB

class OBB(
    val center: ObbPoint,
    val size: Size,
    val rotation: Float // in degrees
) {
    // Convert rotation from degrees to radians
    private val rotationRad = rotation * PI / 180.0

    // Calculate rotation matrix components
    private val cos = cos(rotationRad).toFloat()
    private val sin = sin(rotationRad).toFloat()

    /*
     * Corners, axes and the enclosing AABB are computed on first use and kept. An OBB is immutable,
     * and `intersects` used to rebuild the corner list once per projection axis -- four lists and
     * sixteen points per box per pair test, on the collision detector's hot path.
     *
     * On first use rather than at construction: every laid-out symbol carries a box of its own
     * (`LabelPlacement.obb`) whose size and angle placement reads but whose corners it never asks
     * for -- it tests boxes projected to the viewport instead. Built eagerly, those were eight points,
     * two lists and an AABB per symbol, which on a map drawing every tree as a symbol was a third of
     * the symbols' memory. Unsynchronized: a box is read by one placement pass at a time, and two
     * first uses racing would only compute the same values twice.
     */
    private var cachedCorners: List<ObbPoint>? = null
    private var cachedAxes: List<ObbPoint>? = null
    private var cachedAabb: AABB? = null

    private val cornerList: List<ObbPoint>
        get() = cachedCorners ?: run {
            val halfWidth = size.width / 2
            val halfHeight = size.height / 2
            // The corners before rotation, (-w, -h), (w, -h), (w, h), (-w, h), rotated and translated.
            listOf(
                corner(-halfWidth, -halfHeight),
                corner(halfWidth, -halfHeight),
                corner(halfWidth, halfHeight),
                corner(-halfWidth, halfHeight),
            ).also { cachedCorners = it }
        }

    private fun corner(x: Float, y: Float) = ObbPoint(
        x = center.x + x * cos - y * sin,
        y = center.y + x * sin + y * cos
    )

    private val axisList: List<ObbPoint>
        get() = cachedAxes ?: listOf(
            ObbPoint(cos, sin),      // First axis
            ObbPoint(-sin, cos)      // Second axis (perpendicular to first)
        ).also { cachedAxes = it }

    private val boundingBox: AABB
        get() = cachedAabb ?: cornerList.let { corners ->
            AABB(
                minX = corners.minOf { it.x },
                minY = corners.minOf { it.y },
                maxX = corners.maxOf { it.x },
                maxY = corners.maxOf { it.y }
            )
        }.also { cachedAabb = it }

    // Get the four corners of the OBB
    fun getCorners(): List<ObbPoint> = cornerList

    // Get the axes of the OBB (normalized)
    fun getAxes(): List<ObbPoint> = axisList

    // Project a point onto an axis
    private fun projectPoint(obbPoint: ObbPoint, axis: ObbPoint): Float {
        return obbPoint.x * axis.x + obbPoint.y * axis.y
    }

    // Project all corners onto an axis, without allocating
    private fun projectOnto(axis: ObbPoint, into: FloatArray) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (corner in cornerList) {
            val p = projectPoint(corner, axis)
            if (p < min) min = p
            if (p > max) max = p
        }
        into[0] = min
        into[1] = max
    }

    // Check if two OBBs intersect using Separating Axis Theorem
    fun intersects(other: OBB): Boolean {
        val a = FloatArray(2)
        val b = FloatArray(2)

        // Project both OBBs onto each of the four axes
        for (i in 0 until 4) {
            val axis = if (i < 2) axisList[i] else other.axisList[i - 2]
            projectOnto(axis, a)
            other.projectOnto(axis, b)

            // If there is a gap, the OBBs do not intersect. Touching counts as intersecting, which
            // is what upstream's `_queryCell` box test does with its inclusive bounds.
            if (a[1] < b[0] || b[1] < a[0]) {
                return false
            }
        }

        // If no gap was found on any axis, the OBBs intersect
        return true
    }

    // Get the AABB that contains this OBB
    fun getAABB(): AABB = boundingBox
}
