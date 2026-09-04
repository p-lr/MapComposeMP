package ovh.plrapps.mapcompose.vector.renderer.collision

import ovh.plrapps.mapcompose.vector.utils.rtree.AABB
import ovh.plrapps.mapcompose.vector.utils.rtree.Rtree

/**
 * The viewport's placed symbols, and the test a candidate has to pass to join them.
 *
 * This is upstream's `CollisionIndex` (`symbol/collision_index.ts`) reduced to what a map with no
 * camera pitch needs: one R-tree of oriented boxes instead of two `GridIndex`es of screen-aligned
 * ones. The two-step shape is upstream's and matters -- [wouldCollide] only queries, [insert] only
 * inserts -- because an icon and its label have to be tested apart and then admitted together, or
 * the label would collide with the icon it belongs to.
 *
 * A candidate's shape is either its [LabelPlacement.obb] or, when the placement pass supplied one,
 * its [LabelPlacement.circles] chain -- upstream's `placeCollisionBox` against
 * `placeCollisionCircles`. Circles are inserted one R-tree entry each, as `insertCollisionCircles`
 * inserts one grid circle each, so the broad phase stays as tight as upstream's; `Rtree.search`
 * returns a set, so the entries of one chain collapse back to one candidate.
 *
 * `*-ignore-placement` is *not* `*-allow-overlap`: upstream's `insertCollisionBox`
 * (`collision_index.ts:421-433`) routes such a box to `ignoredGrid` rather than `grid`, so the
 * symbol stops blocking others while still being tested against them. [insert] is therefore the
 * only method that looks at [LabelPlacement.ignorePlacement]; there is no second tree here because
 * nothing else queries the ignored one -- upstream's only reader of `ignoredGrid` is
 * `queryRenderedSymbols`, which this port does not have.
 *
 * Two more of upstream's per-symbol gates are deliberately absent, both for want of a camera:
 * `perspectiveRatioCutoff` (no pitch, so no shrinking towards the horizon) and the occlusion tests
 * (no globe). `isOffscreen` is absent for a different reason -- its only upstream consumer is the
 * `skipFade` flag of the placement fade, and placement here is binary.
 *
 * @param viewportWidth on-screen width in pixels; null leaves the index unbounded horizontally.
 * @param viewportHeight on-screen height in pixels; null leaves the index unbounded vertically.
 */
class CollisionDetector(
    viewportWidth: Float? = null,
    viewportHeight: Float? = null,
) {
    companion object {
        /**
         * Upstream's `viewportPadding` (`symbol/collision_index.ts`), with its reason:
         *
         * > When a symbol crosses the edge that causes it to be included in collision detection, it
         * > will cause changes in the symbols around it. This constant specifies how many pixels to
         * > pad the edge of the viewport for collision detection so that the bulk of the changes
         * > occur offscreen. Making this constant greater increases label stability, but it's
         * > expensive.
         */
        const val VIEWPORT_PADDING: Float = 100f
    }

    private val rtree = Rtree<LabelPlacement>()

    /*
     * Upstream sizes its grid `width + 2 * viewportPadding` by `height + 2 * viewportPadding` and
     * offsets every projected point by `+viewportPadding`, so its grid runs from 0. Screen
     * coordinates here are the viewport's own, origin top-left, so the same region is the viewport
     * rectangle grown by the padding on each side.
     */
    private val gridLeft = if (viewportWidth == null) Float.NEGATIVE_INFINITY else -VIEWPORT_PADDING
    private val gridRight = if (viewportWidth == null) Float.POSITIVE_INFINITY else viewportWidth + VIEWPORT_PADDING
    private val gridTop = if (viewportHeight == null) Float.NEGATIVE_INFINITY else -VIEWPORT_PADDING
    private val gridBottom = if (viewportHeight == null) Float.POSITIVE_INFINITY else viewportHeight + VIEWPORT_PADDING

    /**
     * Port of `isInsideGrid` (`symbol/collision_index.ts`), translated out of upstream's
     * padding-offset frame. Inclusive at the low edge and strict at the high one, as upstream is.
     */
    fun isInsideGrid(bounds: AABB): Boolean =
        bounds.maxX >= gridLeft && bounds.minX < gridRight &&
            bounds.maxY >= gridTop && bounds.minY < gridBottom

    private fun boundsOf(label: LabelPlacement): AABB {
        val circles = label.circles
        return if (circles.isNullOrEmpty()) label.obb.getAABB() else circles.enclosingAabb()
    }

    fun wouldCollide(label: LabelPlacement): Boolean {
        /* Upstream folds `!isInsideGrid` into `unplaceable` *before* the overlap mode is consulted,
         * so a symbol off the padded grid is refused even with `*-allow-overlap`. */
        if (!isInsideGrid(boundsOf(label))) return true
        if (label.overlapMode == OverlapMode.Always) return false

        val candidates = rtree.search(boundsOf(label))
        return candidates.any { existing ->
            shapesIntersect(label, existing) &&
            when (label.overlapMode) {
                // Never: blocked by any symbol in the tree
                OverlapMode.Never -> true
                // Cooperative: blocked only by Never symbols; can overlap other Cooperative/Always
                OverlapMode.Cooperative -> existing.overlapMode == OverlapMode.Never
                // Always: never blocked (unreachable, handled above)
                OverlapMode.Always -> false
            }
        }
    }

    fun tryPlaceLabel(label: LabelPlacement): Boolean {
        if (wouldCollide(label)) return false
        insert(label)
        return true
    }

    /** Admits a label that has already passed [wouldCollide]; an ignore-placement one blocks nothing. */
    fun insert(label: LabelPlacement) {
        if (label.ignorePlacement) return
        val circles = label.circles
        if (circles.isNullOrEmpty()) {
            if (!isInsideGrid(label.obb.getAABB())) return
            rtree.insert(label.obb.getAABB(), label)
        } else {
            if (!isInsideGrid(circles.enclosingAabb())) return
            /* One entry per circle, as `insertCollisionCircles` does; `search` returns a set, so the
             * entries of one chain collapse back to one candidate in the broad phase. */
            for (circle in circles) rtree.insert(circle.aabb(), label)
        }
    }

    fun clear() {
        rtree.clear()
    }
}
