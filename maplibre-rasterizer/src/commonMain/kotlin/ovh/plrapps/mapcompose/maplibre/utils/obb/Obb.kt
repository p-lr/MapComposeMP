package ovh.plrapps.mapcompose.maplibre.utils.obb

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import ovh.plrapps.mapcompose.maplibre.utils.rtree.AABB

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

    // Get the four corners of the OBB
    fun getCorners(): List<ObbPoint> {
        val halfWidth = size.width / 2
        val halfHeight = size.height / 2

        // Calculate the corners before rotation
        val corners = listOf(
            ObbPoint(-halfWidth, -halfHeight),
            ObbPoint(halfWidth, -halfHeight),
            ObbPoint(halfWidth, halfHeight),
            ObbPoint(-halfWidth, halfHeight)
        )

        // Rotate and translate each corner
        return corners.map { corner ->
            ObbPoint(
                x = center.x + corner.x * cos - corner.y * sin,
                y = center.y + corner.x * sin + corner.y * cos
            )
        }
    }

    // Get the axes of the OBB (normalized)
    fun getAxes(): List<ObbPoint> {
        return listOf(
            ObbPoint(cos, sin),      // First axis
            ObbPoint(-sin, cos)      // Second axis (perpendicular to first)
        )
    }

    // Project a point onto an axis
    private fun projectPoint(obbPoint: ObbPoint, axis: ObbPoint): Float {
        return obbPoint.x * axis.x + obbPoint.y * axis.y
    }

    // Project all corners onto an axis
    private fun projectOBB(axis: ObbPoint): Pair<Float, Float> {
        val corners = getCorners()
        val projections = corners.map { projectPoint(it, axis) }
        return Pair(
            projections.minOrNull() ?: 0f,
            projections.maxOrNull() ?: 0f
        )
    }

    // Check if two OBBs intersect using Separating Axis Theorem
    fun intersects(other: OBB): Boolean {
        // Get all axes to check
        val axes = getAxes() + other.getAxes()

        // Project both OBBs onto each axis
        for (axis in axes) {
            val (min1, max1) = projectOBB(axis)
            val (min2, max2) = other.projectOBB(axis)

            // If there is a gap, the OBBs do not intersect
            if (max1 < min2 || max2 < min1) {
                return false
            }
        }

        // If no gap was found on any axis, the OBBs intersect
        return true
    }

    // Get the AABB that contains this OBB
    fun getAABB(): AABB {
        val corners = getCorners()
        val xs = corners.map { it.x }
        val ys = corners.map { it.y }
        
        return AABB(
            minX = xs.minOrNull() ?: 0f,
            minY = ys.minOrNull() ?: 0f,
            maxX = xs.maxOrNull() ?: 0f,
            maxY = ys.maxOrNull() ?: 0f
        )
    }
}
