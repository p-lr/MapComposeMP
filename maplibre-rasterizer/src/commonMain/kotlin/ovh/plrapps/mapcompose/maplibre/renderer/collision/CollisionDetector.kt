package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect

class CollisionDetector {
    val placedLabels = mutableListOf<LabelPlacement>()
    
    fun hasCollision(placement: LabelPlacement): Boolean {
        if (placement.allowOverlap) return false
        
        val toRemove = mutableListOf<LabelPlacement>()
        var hasHigherPriority = false
        for (existing in placedLabels) {
            if (existing.ignorePlacement || existing.allowOverlap) continue
            if (placement.bounds.intersects(existing.bounds)) {
                if (placement.priority > existing.priority) {
                    toRemove.add(existing)
                    hasHigherPriority = true
                } else {
                    return true // There is an intersection with a higher priority or equal one
                }
            }
        }
        // We remove lower priority ones
        placedLabels.removeAll(toRemove)
        return false
    }
    
    fun addPlacement(placement: LabelPlacement) {
        placedLabels.add(placement)
    }
    
    fun clear() {
        placedLabels.clear()
    }
}

fun Rect.intersects(other: Rect): Boolean {
    return this.left < other.right &&
           this.right > other.left &&
           this.top < other.bottom &&
           this.bottom > other.top
} 