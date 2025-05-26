package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.maplibre.utils.rtree.Rtree

class CollisionDetector {
    private val rtree = Rtree<LabelPlacement>()

    fun tryPlaceLabel(label: LabelPlacement): Boolean {
        if (label.ignorePlacement) {
            return true
        }
        if (label.allowOverlap) {
            rtree.insert(label.obb.getAABB(), label)
            return true
        }

        val candidates = rtree.search(label.obb.getAABB())

        for (existing in candidates) {
            if (existing.allowOverlap) {
                continue
            }
            if (label.obb.intersects(existing.obb)) {
                if (label.priority <= existing.priority) {
                    return false
                }
            }
        }

        rtree.insert(label.obb.getAABB(), label)
        return true
    }
}

fun Rect.intersects(other: Rect): Boolean {
    return this.left < other.right &&
           this.right > other.left &&
           this.top < other.bottom &&
           this.bottom > other.top
} 