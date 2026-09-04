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
     * Corners, axes and the enclosing AABB are computed once and kept. An OBB is immutable, and
     * `intersects` used to rebuild the corner list once per projection axis -- four lists and
     * sixteen points per box per pair test, on the collision detector's hot path.
     */
    private val corners: List<ObbPoint> = run {
        val halfWidth = size.width / 2
        val halfHeight = size.height / 2

        // Calculate the corners before rotation
        val local = listOf(
            ObbPoint(-halfWidth, -halfHeight),
            ObbPoint(halfWidth, -halfHeight),
            ObbPoint(halfWidth, halfHeight),
            ObbPoint(-halfWidth, halfHeight)
        )

        // Rotate and translate each corner
        local.map { corner ->
            ObbPoint(
                x = center.x + corner.x * cos - corner.y * sin,
                y = center.y + corner.x * sin + corner.y * cos
            )
        }
    }

    private val axes: List<ObbPoint> = listOf(
        ObbPoint(cos, sin),      // First axis
        ObbPoint(-sin, cos)      // Second axis (perpendicular to first)
    )

    private val aabb: AABB = AABB(
        minX = corners.minOf { it.x },
        minY = corners.minOf { it.y },
        maxX = corners.maxOf { it.x },
        maxY = corners.maxOf { it.y }
    )

    // Get the four corners of the OBB
    fun getCorners(): List<ObbPoint> = corners

    // Get the axes of the OBB (normalized)
    fun getAxes(): List<ObbPoint> = axes

    // Project a point onto an axis
    private fun projectPoint(obbPoint: ObbPoint, axis: ObbPoint): Float {
        return obbPoint.x * axis.x + obbPoint.y * axis.y
    }

    // Project all corners onto an axis, without allocating
    private fun projectOnto(axis: ObbPoint, into: FloatArray) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (corner in corners) {
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
            val axis = if (i < 2) axes[i] else other.axes[i - 2]
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
    fun getAABB(): AABB = aabb
}
