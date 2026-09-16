package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.min
import kotlin.math.sqrt

/**
 * An approximation of a polygon's pole of inaccessibility -- the interior point furthest from any
 * edge, which is where a label goes.
 *
 * Port of `src/util/find_pole_of_inaccessibility.ts`, itself `mapbox/polylabel`: cover the polygon
 * with cells, keep them in a queue ordered by the best distance a cell could still hold, and split
 * the most promising one until no cell can beat the best found by more than [precision].
 *
 * [polygonRings] is one polygon: its exterior ring first, then its holes, which is what
 * `classifyRings` groups and what `GeometryDecoders.decodePolygons` hands out. Coordinates are in
 * whatever space the caller works in; [precision] is in that same unit -- `symbol_layout.ts` passes
 * 16, "2 pixels" at `EXTENT`.
 */
fun findPoleOfInaccessibility(
    polygonRings: List<List<Pair<Float, Float>>>,
    precision: Double = 1.0,
): Pair<Float, Float>? {
    val outer = polygonRings.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for ((x, y) in outer) {
        if (x < minX) minX = x.toDouble()
        if (y < minY) minY = y.toDouble()
        if (x > maxX) maxX = x.toDouble()
        if (y > maxY) maxY = y.toDouble()
    }

    val cellSize = min(maxX - minX, maxY - minY)
    if (cellSize == 0.0) return minX.toFloat() to minY.toFloat()
    var h = cellSize / 2

    // A queue of cells ordered by their "potential" -- the greatest distance the cell could hold.
    val cellQueue = MaxPotentialQueue()

    // Cover the polygon with initial cells.
    var x = minX
    while (x < maxX) {
        var y = minY
        while (y < maxY) {
            cellQueue.push(Cell(x + h, y + h, h, polygonRings))
            y += cellSize
        }
        x += cellSize
    }

    // Take the centroid as the first best guess.
    val centroidCell = centroidCell(polygonRings)
    var bestCell = centroidCell

    while (cellQueue.isNotEmpty()) {
        val cell = cellQueue.pop()

        if (cell.d > bestCell.d || bestCell.d == 0.0) bestCell = cell

        // No chance of a better solution below this cell.
        if (cell.max - bestCell.d <= precision) continue

        h = cell.h / 2
        cellQueue.push(Cell(cell.x - h, cell.y - h, h, polygonRings))
        cellQueue.push(Cell(cell.x + h, cell.y - h, h, polygonRings))
        cellQueue.push(Cell(cell.x - h, cell.y + h, h, polygonRings))
        cellQueue.push(Cell(cell.x + h, cell.y + h, h, polygonRings))
    }

    /* For a convex or nearly-convex polygon the centroid reads better than the mathematical pole:
     * rounded coordinates break the shape's symmetry and let the pole drift well off centre for a
     * distance-to-edge that is barely better. Upstream's own preference, same wording. */
    if (centroidCell.d > 0 && bestCell.d - centroidCell.d <= precision) {
        return centroidCell.x.toFloat() to centroidCell.y.toFloat()
    }
    return bestCell.x.toFloat() to bestCell.y.toFloat()
}

/** One square of the search: its centre, its half-size, and what it is worth. */
private class Cell(
    val x: Double,
    val y: Double,
    val h: Double,
    polygon: List<List<Pair<Float, Float>>>,
) {
    /** Distance from the centre to the polygon, negative outside it. */
    val d: Double = pointToPolygonDist(x, y, polygon)

    /** The greatest distance anything in this cell could have. */
    val max: Double = d + h * SQRT2

    private companion object {
        val SQRT2 = sqrt(2.0)
    }
}

/**
 * A binary max-heap over [Cell.max].
 *
 * Upstream uses `tinyqueue`; there is no priority queue in `kotlin.collections`, and the queue is
 * small and entirely private to the search.
 */
private class MaxPotentialQueue {
    private val items = ArrayList<Cell>()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    fun push(cell: Cell) {
        items.add(cell)
        var index = items.size - 1
        while (index > 0) {
            val parent = (index - 1) / 2
            if (items[parent].max >= items[index].max) break
            items.swap(parent, index)
            index = parent
        }
    }

    fun pop(): Cell {
        val top = items[0]
        val last = items.removeAt(items.size - 1)
        if (items.isEmpty()) return top
        items[0] = last
        var index = 0
        while (true) {
            val left = 2 * index + 1
            if (left >= items.size) break
            val right = left + 1
            var largest = left
            if (right < items.size && items[right].max > items[left].max) largest = right
            if (items[index].max >= items[largest].max) break
            items.swap(index, largest)
            index = largest
        }
        return top
    }

    private fun ArrayList<Cell>.swap(a: Int, b: Int) {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }
}

/**
 * Signed distance from a point to the polygon: positive inside, negative outside.
 *
 * Ray casting decides the sign, the nearest segment the magnitude -- upstream's
 * `pointToPolygonDist`, holes included, since a hole's edges are edges too.
 */
private fun pointToPolygonDist(
    px: Double,
    py: Double,
    polygon: List<List<Pair<Float, Float>>>,
): Double {
    var inside = false
    var minDistSq = Double.POSITIVE_INFINITY

    for (ring in polygon) {
        val len = ring.size
        if (len == 0) continue
        var j = len - 1
        for (i in 0 until len) {
            val ax = ring[i].first.toDouble()
            val ay = ring[i].second.toDouble()
            val bx = ring[j].first.toDouble()
            val by = ring[j].second.toDouble()

            if ((ay > py) != (by > py) && px < (bx - ax) * (py - ay) / (by - ay) + ax) {
                inside = !inside
            }
            minDistSq = min(minDistSq, distToSegmentSquared(px, py, ax, ay, bx, by))
            j = i
        }
    }

    if (minDistSq == Double.POSITIVE_INFINITY) return 0.0
    return (if (inside) 1.0 else -1.0) * sqrt(minDistSq)
}

private fun distToSegmentSquared(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Double {
    var cx = ax
    var cy = ay
    val dx = bx - ax
    val dy = by - ay

    if (dx != 0.0 || dy != 0.0) {
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        if (t > 1) {
            cx = bx
            cy = by
        } else if (t > 0) {
            cx += dx * t
            cy += dy * t
        }
    }

    val ex = px - cx
    val ey = py - cy
    return ex * ex + ey * ey
}

/** The exterior ring's centroid, as a zero-sized cell -- upstream's `getCentroidCell`. */
private fun centroidCell(polygon: List<List<Pair<Float, Float>>>): Cell {
    var area = 0.0
    var x = 0.0
    var y = 0.0
    val points = polygon[0]
    val len = points.size
    var j = len - 1
    for (i in 0 until len) {
        val ax = points[i].first.toDouble()
        val ay = points[i].second.toDouble()
        val bx = points[j].first.toDouble()
        val by = points[j].second.toDouble()
        val f = ax * by - bx * ay
        x += (ax + bx) * f
        y += (ay + by) * f
        area += f * 3
        j = i
    }
    if (area == 0.0) return Cell(points[0].first.toDouble(), points[0].second.toDouble(), 0.0, polygon)
    return Cell(x / area, y / area, 0.0, polygon)
}
