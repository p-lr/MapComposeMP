package ovh.plrapps.mapcompose.maplibre.utils.rtree

sealed class RTreeNode<T> {
    data class Leaf<T>(
        val entries: MutableList<Pair<AABB, T>> = mutableListOf()
    ) : RTreeNode<T>()

    data class NonLeaf<T>(
        val children: MutableList<Pair<RTreeNode<T>, AABB>> = mutableListOf()
    ) : RTreeNode<T>()
}

class Rtree<T>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val minEntries: Int = DEFAULT_MIN_ENTRIES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {
    companion object {
        private const val DEFAULT_MAX_ENTRIES = 32
        private const val DEFAULT_MIN_ENTRIES = 2
        private const val DEFAULT_MAX_DEPTH = Int.MAX_VALUE
        private val EMPTY_AABB = AABB(0f, 0f, 0f, 0f)
    }

    private var root: RTreeNode<T> = RTreeNode.Leaf()
    private var size: Int = 0
    private var depth: Int = 0
    private val tempResults = mutableSetOf<T>()
    private val searchStack = mutableListOf<RTreeNode<T>>()

    fun insert(bounds: AABB, item: T) {
        val splitResult = insertInternal(root, bounds, item, 0)
        if (splitResult != null) {
            val newRoot = RTreeNode.NonLeaf<T>()
            for (child in splitResult) {
                newRoot.children.add(child to calcBounds(child))
            }
            root = newRoot
            depth++
        }
        size++
    }

    private fun chooseSubtree(node: RTreeNode.NonLeaf<T>, bounds: AABB): Int {
        var bestChild = 0
        var minOverlap = Float.MAX_VALUE
        var minArea = Float.MAX_VALUE
        
        for (i in node.children.indices) {
            val (_, childBounds) = node.children[i]
            val newBounds = childBounds.union(bounds)
            
            var overlap = 0f
            for (j in node.children.indices) {
                if (i != j) {
                    val (_, otherBounds) = node.children[j]
                    if (newBounds.intersects(otherBounds)) {
                        overlap += newBounds.intersectionArea(otherBounds)
                    }
                }
            }
            
            if (overlap < minOverlap) {
                minOverlap = overlap
                minArea = newBounds.area() - childBounds.area()
                bestChild = i
            } else if (overlap == minOverlap) {
                // With equal overlap, we select the minimum increase in area
                val areaIncrease = newBounds.area() - childBounds.area()
                if (areaIncrease < minArea) {
                    minArea = areaIncrease
                    bestChild = i
                }
            }
        }
        
        return bestChild
    }

    private fun <T> findSeeds(items: List<T>, getBounds: (T) -> AABB): Pair<Int, Int> {
        var maxDistance = Float.MIN_VALUE
        var seed1 = 0
        var seed2 = 1
        for (i in items.indices) {
            for (j in (i + 1) until items.size) {
                val bounds1 = getBounds(items[i])
                val bounds2 = getBounds(items[j])
                val distance = bounds1.area() + bounds2.area() - bounds1.union(bounds2).area()
                if (distance > maxDistance) {
                    maxDistance = distance
                    seed1 = i
                    seed2 = j
                }
            }
        }
        return seed1 to seed2
    }

    private fun insertInternal(node: RTreeNode<T>, bounds: AABB, item: T, currentDepth: Int): List<RTreeNode<T>>? {
        if (currentDepth >= maxDepth) {
            throw IllegalStateException("Maximum tree depth exceeded")
        }

        return when (node) {
            is RTreeNode.Leaf -> {
                node.entries.add(bounds to item)
                if (node.entries.size > maxEntries) splitLeaf(node).toList() else null
            }
            is RTreeNode.NonLeaf -> {
                val bestChild = chooseSubtree(node, bounds)
                val (child, _) = node.children[bestChild]
                val splitResult = insertInternal(child, bounds, item, currentDepth + 1)
                
                if (splitResult != null) {
                    node.children.removeAt(bestChild)
                    for (newChild in splitResult) {
                        node.children.add(newChild to calcBounds(newChild))
                    }
                    
                    if (node.children.size > maxEntries) {
                        splitNonLeaf(node).toList()
                    } else {
                        null
                    }
                } else {
                    node.children[bestChild] = child to calcBounds(child)
                    null
                }
            }
        }
    }

    private fun splitLeaf(node: RTreeNode.Leaf<T>): Pair<RTreeNode<T>, RTreeNode<T>> {
        if (node.entries.size <= 1) {
            return node to RTreeNode.Leaf()
        }
        
        val (seed1, seed2) = findSeeds(node.entries) { it.first }
        
        val leaf1 = RTreeNode.Leaf<T>()
        val leaf2 = RTreeNode.Leaf<T>()
        
        var bounds1 = node.entries[seed1].first
        var bounds2 = node.entries[seed2].first
        
        leaf1.entries.add(node.entries[seed1])
        leaf2.entries.add(node.entries[seed2])
        
        val remaining = node.entries.filterIndexed { idx, _ -> idx != seed1 && idx != seed2 }
            .sortedByDescending { entry ->
                val area1 = bounds1.union(entry.first).area() - bounds1.area()
                val area2 = bounds2.union(entry.first).area() - bounds2.area()
                maxOf(area1, area2)
            }
        
        for (entry in remaining) {
            val area1 = bounds1.union(entry.first).area() - bounds1.area()
            val area2 = bounds2.union(entry.first).area() - bounds2.area()
            
            if (area1 < area2 || (area1 == area2 && leaf1.entries.size <= leaf2.entries.size)) {
                leaf1.entries.add(entry)
                bounds1 = bounds1.union(entry.first)
            } else {
                leaf2.entries.add(entry)
                bounds2 = bounds2.union(entry.first)
            }
        }
        
        node.entries.clear()
        return leaf1 to leaf2
    }

    private fun splitNonLeaf(node: RTreeNode.NonLeaf<T>): Pair<RTreeNode<T>, RTreeNode<T>> {
        if (node.children.size <= 1) {
            return node to RTreeNode.NonLeaf()
        }
        
        val (seed1, seed2) = findSeeds(node.children) { it.second }
        
        val n1 = RTreeNode.NonLeaf<T>()
        val n2 = RTreeNode.NonLeaf<T>()
        
        var bounds1 = node.children[seed1].second
        var bounds2 = node.children[seed2].second
        
        n1.children.add(node.children[seed1])
        n2.children.add(node.children[seed2])
        
        val remaining = node.children.filterIndexed { idx, _ -> idx != seed1 && idx != seed2 }
            .sortedByDescending { (_, bound) ->
                val area1 = bounds1.union(bound).area() - bounds1.area()
                val area2 = bounds2.union(bound).area() - bounds2.area()
                maxOf(area1, area2)
            }
        
        for ((child, bound) in remaining) {
            val area1 = bounds1.union(bound).area() - bounds1.area()
            val area2 = bounds2.union(bound).area() - bounds2.area()
            
            if (area1 < area2 || (area1 == area2 && n1.children.size <= n2.children.size)) {
                n1.children.add(child to bound)
                bounds1 = bounds1.union(bound)
            } else {
                n2.children.add(child to bound)
                bounds2 = bounds2.union(bound)
            }
        }
        
        node.children.clear()
        return n1 to n2
    }

    fun search(bounds: AABB): Set<T> {
        if (size == 0) return emptySet()
        tempResults.clear()
        searchInternal(root, bounds)
        return tempResults.toSet()
    }

    private fun searchInternal(node: RTreeNode<T>, bounds: AABB) {
        searchStack.clear()
        searchStack.add(node)
        
        while (searchStack.isNotEmpty()) {
            val current = searchStack.removeAt(searchStack.lastIndex)
            when (current) {
                is RTreeNode.Leaf -> {
                    for ((entryBounds, item) in current.entries) {
                        if (bounds.intersects(entryBounds)) {
                            tempResults.add(item)
                        }
                    }
                }
                is RTreeNode.NonLeaf -> {
                    for ((child, childBounds) in current.children) {
                        if (bounds.intersects(childBounds)) {
                            searchStack.add(child)
                        }
                    }
                }
            }
        }
    }

    fun size(): Int = size

    fun depth(): Int = depth
    
    fun clear() {
        root = RTreeNode.Leaf()
        size = 0
        depth = 0
        tempResults.clear()
        searchStack.clear()
    }

    private fun calcBounds(node: RTreeNode<T>): AABB {
        return when (node) {
            is RTreeNode.Leaf -> {
                if (node.entries.isEmpty()) EMPTY_AABB
                else node.entries.fold(node.entries[0].first) { acc, (bounds, _) -> acc.union(bounds) }
            }
            is RTreeNode.NonLeaf -> {
                if (node.children.isEmpty()) EMPTY_AABB
                else node.children.fold(node.children[0].second) { acc, (_, bounds) -> acc.union(bounds) }
            }
        }
    }
}

private fun AABB.area(): Float = (maxX - minX) * (maxY - minY)

private fun AABB.intersectionArea(other: AABB): Float {
    if (!intersects(other)) return 0f
    val width = minOf(maxX, other.maxX) - maxOf(minX, other.minX)
    val height = minOf(maxY, other.maxY) - maxOf(minY, other.minY)
    return width * height
}
