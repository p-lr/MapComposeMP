package ovh.plrapps.mapcompose.vector.spec.style.expression.geometry

import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.tan

/**
 * Geometry helpers for the `within` and `distance` expressions.
 *
 * Ported from `maplibre-style-spec/src/util/geometry_util.ts`. All coordinates are in *global*
 * tile units (`tileX * EXTENT + localX`), which is what lets a polygon given in lng/lat be compared
 * against MVT feature geometry.
 */

const val EXTENT = 8192

/** `[minX, minY, maxX, maxY]`. */
typealias BBox = DoubleArray

fun newBBox(): BBox = doubleArrayOf(
    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
)

/** A 2-element `[x, y]` coordinate. */
typealias Coord = DoubleArray

private fun mercatorXfromLng(lng: Double): Double = (180 + lng) / 360

private fun mercatorYfromLat(lat: Double): Double =
    (180 - (180 / PI) * ln(tan(PI / 4 + (lat * PI) / 360))) / 360

private fun lngFromMercatorX(mercatorX: Double): Double = mercatorX * 360 - 180

private fun latFromMercatorY(mercatorY: Double): Double =
    (360 / PI) * atan(exp(((180 - mercatorY * 360) * PI) / 180)) - 90

fun getTileCoordinates(position: List<Double>, canonical: CanonicalTileId): Coord {
    val x = mercatorXfromLng(position[0])
    val y = mercatorYfromLat(position[1])
    val tilesAtZoom = (1 shl canonical.z).toDouble()
    return doubleArrayOf(round(x * tilesAtZoom * EXTENT), round(y * tilesAtZoom * EXTENT))
}

fun getLngLatFromTileCoord(coord: Coord, canonical: CanonicalTileId): List<Double> {
    val tilesAtZoom = (1 shl canonical.z).toDouble()
    val x = (coord[0] / EXTENT + canonical.x) / tilesAtZoom
    val y = (coord[1] / EXTENT + canonical.y) / tilesAtZoom
    return listOf(lngFromMercatorX(x), latFromMercatorY(y))
}

fun updateBBox(bbox: BBox, coord: Coord) {
    bbox[0] = minOf(bbox[0], coord[0])
    bbox[1] = minOf(bbox[1], coord[1])
    bbox[2] = maxOf(bbox[2], coord[0])
    bbox[3] = maxOf(bbox[3], coord[1])
}

fun boxWithinBox(bbox1: BBox, bbox2: BBox): Boolean {
    if (bbox1[0] <= bbox2[0]) return false
    if (bbox1[2] >= bbox2[2]) return false
    if (bbox1[1] <= bbox2[1]) return false
    if (bbox1[3] >= bbox2[3]) return false
    return true
}

fun rayIntersect(p: Coord, p1: Coord, p2: Coord): Boolean =
    (p1[1] > p[1]) != (p2[1] > p[1]) &&
            p[0] < ((p2[0] - p1[0]) * (p[1] - p1[1])) / (p2[1] - p1[1]) + p1[0]

private fun pointOnBoundary(p: Coord, p1: Coord, p2: Coord): Boolean {
    val x1 = p[0] - p1[0]
    val y1 = p[1] - p1[1]
    val x2 = p[0] - p2[0]
    val y2 = p[1] - p2[1]
    return x1 * y2 - x2 * y1 == 0.0 && x1 * x2 <= 0 && y1 * y2 <= 0
}

private fun perp(v1: Coord, v2: Coord): Double = v1[0] * v2[1] - v1[1] * v2[0]

/** Whether p1 and p2 lie on different sides of the segment q1->q2. */
private fun twoSided(p1: Coord, p2: Coord, q1: Coord, q2: Coord): Boolean {
    val x1 = p1[0] - q1[0]
    val y1 = p1[1] - q1[1]
    val x2 = p2[0] - q1[0]
    val y2 = p2[1] - q1[1]
    val x3 = q2[0] - q1[0]
    val y3 = q2[1] - q1[1]
    val det1 = x1 * y3 - x3 * y1
    val det2 = x2 * y3 - x3 * y2
    return (det1 > 0 && det2 < 0) || (det1 < 0 && det2 > 0)
}

fun segmentIntersectSegment(a: Coord, b: Coord, c: Coord, d: Coord): Boolean {
    // Two parallel segments never intersect. The precondition is that a and b are inside the
    // polygon, so a segment parallel to an edge cannot cross it.
    val vectorP = doubleArrayOf(b[0] - a[0], b[1] - a[1])
    val vectorQ = doubleArrayOf(d[0] - c[0], d[1] - c[1])
    if (perp(vectorQ, vectorP) == 0.0) return false

    return twoSided(a, b, c, d) && twoSided(c, d, a, b)
}

fun lineIntersectPolygon(p1: Coord, p2: Coord, polygon: List<List<Coord>>): Boolean {
    for (ring in polygon) {
        for (j in 0 until ring.size - 1) {
            if (segmentIntersectSegment(p1, p2, ring[j], ring[j + 1])) return true
        }
    }
    return false
}

/** Ray-casting point-in-polygon test. */
fun pointWithinPolygon(
    point: Coord,
    rings: List<List<Coord>>,
    trueIfOnBoundary: Boolean = false,
): Boolean {
    var inside = false
    for (ring in rings) {
        for (j in 0 until ring.size - 1) {
            if (pointOnBoundary(point, ring[j], ring[j + 1])) return trueIfOnBoundary
            if (rayIntersect(point, ring[j], ring[j + 1])) inside = !inside
        }
    }
    return inside
}

fun pointWithinPolygons(point: Coord, polygons: List<List<List<Coord>>>): Boolean =
    polygons.any { pointWithinPolygon(point, it) }

fun lineStringWithinPolygon(line: List<Coord>, polygon: List<List<Coord>>): Boolean {
    // First, every vertex of the line must be inside the polygon.
    for (point in line) {
        if (!pointWithinPolygon(point, polygon)) return false
    }
    // Second, no segment may cross a polygon edge.
    for (i in 0 until line.size - 1) {
        if (lineIntersectPolygon(line[i], line[i + 1], polygon)) return false
    }
    return true
}

fun lineStringWithinPolygons(line: List<Coord>, polygons: List<List<List<Coord>>>): Boolean =
    polygons.any { lineStringWithinPolygon(line, it) }
