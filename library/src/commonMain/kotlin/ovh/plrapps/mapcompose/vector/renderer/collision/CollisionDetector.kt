package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.vector.utils.rtree.Rtree

class CollisionDetector {
    private val rtree = Rtree<LabelPlacement>()

    fun wouldCollide(label: LabelPlacement): Boolean {
        if (label.ignorePlacement) return false
        if (label.overlapMode == OverlapMode.Always) return false

        val candidates = rtree.search(label.obb.getAABB())
        return candidates.any { existing ->
            !existing.ignorePlacement &&
            label.obb.intersects(existing.obb) &&
            when (label.overlapMode) {
                // Never: blocked by any non-ignore symbol
                OverlapMode.Never -> true
                // Cooperative: blocked only by Never symbols; can overlap other Cooperative/Always
                OverlapMode.Cooperative -> existing.overlapMode == OverlapMode.Never
                // Always: never blocked (unreachable, handled above)
                OverlapMode.Always -> false
            }
        }
    }

    fun tryPlaceLabel(label: LabelPlacement): Boolean {
        if (label.ignorePlacement) return true
        if (wouldCollide(label)) return false
        // Insert everything except ignore-placement labels.
        // Even Always/Cooperative labels are inserted so they can block Never symbols placed later.
        rtree.insert(label.obb.getAABB(), label)
        return true
    }

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