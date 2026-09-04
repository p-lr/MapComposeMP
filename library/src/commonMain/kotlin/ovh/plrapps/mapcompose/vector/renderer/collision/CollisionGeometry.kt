package ovh.plrapps.mapcompose.vector.renderer.collision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.rtree.AABB

/**
 * One circle of a symbol's collision shape.
 *
 * Upstream stores these flat, four numbers at a time, in the array `placeCollisionCircles` builds
 * (`symbol/collision_index.ts`); `insertCollisionCircles` then walks that array in steps of four and
 * hands `(x, y, radius)` to `GridIndex.insertCircle`. The fourth slot is the debug "collision
 * detected" flag, which this port has no use for.
 */
data class CollisionCircle(val x: Float, val y: Float, val radius: Float) {
    fun aabb(): AABB = AABB(x - radius, y - radius, x + radius, y + radius)
}

/** The AABB enclosing every circle of a chain. Empty chains are rejected by the caller. */
internal fun List<CollisionCircle>.enclosingAabb(): AABB {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (c in this) {
        if (c.x - c.radius < minX) minX = c.x - c.radius
        if (c.y - c.radius < minY) minY = c.y - c.radius
        if (c.x + c.radius > maxX) maxX = c.x + c.radius
        if (c.y + c.radius > maxY) maxY = c.y + c.radius
    }
    return AABB(minX, minY, maxX, maxY)
}

/**
 * Circle against circle, the port of `_circlesCollide` (`symbol/grid_index.ts`).
 *
 * The comparison is **strict**, as upstream's is: two circles that exactly touch do *not* collide.
 * [circleAndRectCollide] is inclusive at its boundary instead. That asymmetry is upstream's and is
 * not a bug to fix -- matching it is what keeps a chain's behaviour at a boundary the same as
 * MapLibre's.
 */
fun circlesCollide(x1: Float, y1: Float, r1: Float, x2: Float, y2: Float, r2: Float): Boolean {
    val dx = x2 - x1
    val dy = y2 - y1
    val bothRadii = r1 + r2
    return (bothRadii * bothRadii) > (dx * dx + dy * dy)
}

/**
 * Circle against axis-aligned rectangle, the port of `_circleAndRectCollide`
 * (`symbol/grid_index.ts`), including its two half-extent early-outs and its corner test.
 *
 * Inclusive at the boundary (`<=`), unlike [circlesCollide]; see there.
 */
fun circleAndRectCollide(
    circleX: Float,
    circleY: Float,
    radius: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): Boolean {
    val halfRectWidth = (x2 - x1) / 2f
    val distX = abs(circleX - (x1 + halfRectWidth))
    if (distX > (halfRectWidth + radius)) {
        return false
    }

    val halfRectHeight = (y2 - y1) / 2f
    val distY = abs(circleY - (y1 + halfRectHeight))
    if (distY > (halfRectHeight + radius)) {
        return false
    }

    if (distX <= halfRectWidth || distY <= halfRectHeight) {
        return true
    }

    val dx = distX - halfRectWidth
    val dy = distY - halfRectHeight
    return (dx * dx + dy * dy) <= (radius * radius)
}

/**
 * Circle against oriented box.
 *
 * Upstream has no analogue: its collision boxes are axis-aligned in viewport space, a rotated one
 * having been replaced by its envelope back in `collision_feature.ts` ("Collision features require
 * an 'on-axis' geometry"). This port keeps the real orientation, so the test rotates the circle's
 * centre into the box's frame -- a circle is rotation-invariant -- and is then exactly
 * [circleAndRectCollide] against the box's half-extents.
 */
fun circleAndObbCollide(circle: CollisionCircle, obb: OBB): Boolean {
    val rad = -obb.rotation * PI.toFloat() / 180f
    val c = cos(rad)
    val s = sin(rad)
    val dx = circle.x - obb.center.x
    val dy = circle.y - obb.center.y
    val localX = dx * c - dy * s
    val localY = dx * s + dy * c
    val halfWidth = obb.size.width / 2f
    val halfHeight = obb.size.height / 2f
    return circleAndRectCollide(
        localX, localY, circle.radius,
        -halfWidth, -halfHeight, halfWidth, halfHeight,
    )
}

/**
 * The narrow phase: do these two symbols' collision shapes actually touch?
 *
 * A symbol is either one oriented box or a chain of circles ([LabelPlacement.circles]), which is
 * upstream's `placeCollisionBox` / `placeCollisionCircles` split. All four pairings are covered here
 * so a chain collides with a box exactly as a box collides with a chain.
 */
internal fun shapesIntersect(a: LabelPlacement, b: LabelPlacement): Boolean {
    // An empty chain is no chain: `CollisionDetector` indexes such a label by its box, so the
    // narrow phase has to agree or the two would disagree about what shape the label has.
    val ca = a.circles?.takeIf { it.isNotEmpty() }
    val cb = b.circles?.takeIf { it.isNotEmpty() }
    return when {
        ca == null && cb == null -> a.obb.intersects(b.obb)
        ca != null && cb == null -> ca.any { circleAndObbCollide(it, b.obb) }
        ca == null && cb != null -> cb.any { circleAndObbCollide(it, a.obb) }
        else -> ca!!.any { p ->
            cb!!.any { q -> circlesCollide(p.x, p.y, p.radius, q.x, q.y, q.radius) }
        }
    }
}
