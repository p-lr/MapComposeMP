package ovh.plrapps.mapcompose.maplibre.utils.compose

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.maplibre.utils.rtree.AABB
import ovh.plrapps.mapcompose.maplibre.utils.rtree.Rtree

/**
 * Converts Compose Rect to AABB
 */
fun Rect.toAABB(): AABB {
    return AABB(
        minX = left,
        minY = top,
        maxX = right,
        maxY = bottom
    )
}

/**
 * Inserts a Compose Rect into R-tree with custom data
 * @param id unique identifier for the item
 * @param data custom data to store with the item
 */
fun <T> Rtree<T>.insert(id: String, rect: Rect, data: T) {
    insert(rect.toAABB(), data)
}

/**
 * Searches for items in R-tree that intersect with the given Compose Rect
 * @return list of found items with their data
 */
fun <T> Rtree<T>.search(rect: Rect): Set<T> {
    return search(rect.toAABB())
}