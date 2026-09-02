package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.vector.utils.rtree.Rtree

/**
 * The viewport's placed symbols, and the test a candidate has to pass to join them.
 *
 * This is upstream's `CollisionIndex` (`symbol/collision_index.ts`) reduced to what a map with no
 * camera pitch needs: one R-tree of oriented boxes instead of two `GridIndex`es of screen-aligned
 * ones. The two-step shape is upstream's and matters -- `wouldCollide` only queries, `insert` only
 * inserts -- because an icon and its label have to be tested apart and then admitted together, or
 * the label would collide with the icon it belongs to.
 *
 * `*-ignore-placement` is *not* `*-allow-overlap`: upstream's `insertCollisionBox`
 * (`collision_index.ts:428-433`) routes such a box to `ignoredGrid` rather than `grid`, so the
 * symbol stops blocking others while still being tested against them. [insert] is therefore the
 * only method that looks at [LabelPlacement.ignorePlacement]; there is no second tree here because
 * nothing else queries the ignored one.
 */
class CollisionDetector {
    private val rtree = Rtree<LabelPlacement>()

    fun wouldCollide(label: LabelPlacement): Boolean {
        if (label.overlapMode == OverlapMode.Always) return false

        val candidates = rtree.search(label.obb.getAABB())
        return candidates.any { existing ->
            label.obb.intersects(existing.obb) &&
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
        if (!label.ignorePlacement) {
            rtree.insert(label.obb.getAABB(), label)
        }
    }

    fun clear() {
        rtree.clear()
    }
}

fun Rect.intersects(other: Rect): Boolean {
    return this.left < other.right &&
           this.right > other.left &&
           this.top < other.bottom &&
           this.bottom > other.top
}
