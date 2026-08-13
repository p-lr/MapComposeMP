package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D

/**
 * Classifies an array of rings into polygons with outer rings and holes.
 *
 * Ported from `maplibre-style-spec/src/util/classify_rings.ts`. The `maxRings` pruning upstream
 * performs (a quickselect over ring areas, needed only to bound earcut's cost) is omitted: the only
 * caller here passes `maxRings = 0`, which disables it.
 */
fun classifyRings(rings: List<List<Point2D>>): List<List<List<Point2D>>> {
    if (rings.size <= 1) return listOf(rings)

    val polygons = mutableListOf<List<List<Point2D>>>()
    var polygon: MutableList<List<Point2D>>? = null
    var ccw: Boolean? = null

    for (ring in rings) {
        val area = calculateSignedArea(ring)
        if (area == 0.0) continue

        if (ccw == null) ccw = area < 0

        if (ccw == (area < 0)) {
            polygon?.let { polygons.add(it) }
            polygon = mutableListOf(ring)
        } else {
            polygon?.add(ring)
        }
    }
    polygon?.let { polygons.add(it) }

    return polygons
}

/**
 * Signed area of a polygon ring. Positive areas are exterior rings with clockwise winding;
 * negative areas are interior rings wound counter-clockwise.
 */
private fun calculateSignedArea(ring: List<Point2D>): Double {
    var sum = 0.0
    val len = ring.size
    var j = len - 1
    for (i in 0 until len) {
        val p1 = ring[i]
        val p2 = ring[j]
        sum += (p2.x - p1.x) * (p1.y + p2.y)
        j = i
    }
    return sum
}
