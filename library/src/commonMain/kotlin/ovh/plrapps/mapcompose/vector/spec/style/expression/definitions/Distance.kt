package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.NumberType
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.BBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.CheapRuler
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.Coord
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.TinyQueue
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.boxWithinBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.classifyRings
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.getLngLatFromTileCoord
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.newBBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.pointWithinPolygon
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.segmentIntersectSegment
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.updateBBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValue
import kotlin.math.floor
import kotlin.math.min

/**
 * Distance in metres from the feature to a GeoJSON geometry.
 *
 * Ported from `maplibre-style-spec/src/expression/definitions/distance.ts`. The divide-and-conquer
 * search (a priority queue over index ranges, pruned by bounding-box distance) is reproduced as-is,
 * including its `MinPointsSize` / `MinLinePointsSize` brute-force thresholds.
 */
class Distance(val geojson: Any?, private val geometries: List<SimpleGeometry>) : Expression {

    override val type: ExprType = NumberType

    /** A GeoJSON geometry flattened to its non-`Multi` form. */
    data class SimpleGeometry(val type: String, val coordinates: Any?)

    override fun evaluate(ctx: EvaluationContext): Any {
        val canonical = ctx.canonicalID() ?: return Double.NaN
        if (ctx.geometry().isEmpty()) return Double.NaN

        return when (ctx.geometryType()) {
            "Point" -> pointToGeometryDistance(ctx.geometry(), canonical)
            "LineString" -> lineStringToGeometryDistance(ctx.geometry(), canonical)
            "Polygon" -> polygonToGeometryDistance(ctx.geometry(), canonical)
            else -> Double.NaN
        }
    }

    override fun eachChild(fn: (Expression) -> Unit) = Unit

    override fun outputDefined(): Boolean = true

    // region top-level dispatch

    private fun pointToGeometryDistance(tilePoints: List<List<Point2D>>, canonical: CanonicalTileId): Double {
        val pointPosition = tilePoints.flatten().map { toLngLat(it, canonical) }
        if (tilePoints.isEmpty()) return Double.NaN
        val ruler = CheapRuler(pointPosition[0][1])
        var dist = Double.POSITIVE_INFINITY
        for (geometry in geometries) {
            dist = min(dist, distanceToPointSet(pointPosition, isLine = false, geometry, ruler, dist))
            if (dist == 0.0) return dist
        }
        return dist
    }

    private fun lineStringToGeometryDistance(tileLine: List<List<Point2D>>, canonical: CanonicalTileId): Double {
        val linePositions = tileLine.flatten().map { toLngLat(it, canonical) }
        if (tileLine.isEmpty()) return Double.NaN
        val ruler = CheapRuler(linePositions[0][1])
        var dist = Double.POSITIVE_INFINITY
        for (geometry in geometries) {
            dist = min(dist, distanceToPointSet(linePositions, isLine = true, geometry, ruler, dist))
            if (dist == 0.0) return dist
        }
        return dist
    }

    private fun distanceToPointSet(
        positions: List<Coord>,
        isLine: Boolean,
        geometry: SimpleGeometry,
        ruler: CheapRuler,
        currentMiniDist: Double,
    ): Double = when (geometry.type) {
        "Point" -> pointSetToPointSetDistance(
            positions, isLine, listOf(asCoord(geometry.coordinates)), false, ruler, currentMiniDist,
        )

        "LineString" -> pointSetToPointSetDistance(
            positions, isLine, asLine(geometry.coordinates), true, ruler, currentMiniDist,
        )

        "Polygon" -> pointsToPolygonDistance(
            positions, isLine, asPolygon(geometry.coordinates), ruler, currentMiniDist,
        )

        else -> Double.POSITIVE_INFINITY
    }

    private fun polygonToGeometryDistance(tilePolygon: List<List<Point2D>>, canonical: CanonicalTileId): Double {
        if (tilePolygon.isEmpty() || tilePolygon[0].isEmpty()) return Double.NaN

        val polygons = classifyRings(tilePolygon).map { polygon ->
            polygon.map { ring -> ring.map { toLngLat(it, canonical) } }
        }
        if (polygons.isEmpty() || polygons[0].isEmpty() || polygons[0][0].isEmpty()) return Double.NaN

        val ruler = CheapRuler(polygons[0][0][0][1])
        var dist = Double.POSITIVE_INFINITY
        for (geometry in geometries) {
            for (polygon in polygons) {
                dist = when (geometry.type) {
                    "Point" -> min(
                        dist,
                        pointsToPolygonDistance(
                            listOf(asCoord(geometry.coordinates)), false, polygon, ruler, dist,
                        ),
                    )

                    "LineString" -> min(
                        dist,
                        pointsToPolygonDistance(asLine(geometry.coordinates), true, polygon, ruler, dist),
                    )

                    "Polygon" -> min(
                        dist,
                        polygonToPolygonDistance(polygon, asPolygon(geometry.coordinates), ruler, dist),
                    )

                    else -> dist
                }
                if (dist == 0.0) return dist
            }
        }
        return dist
    }

    private fun toLngLat(p: Point2D, canonical: CanonicalTileId): Coord {
        val lngLat = getLngLatFromTileCoord(doubleArrayOf(p.x, p.y), canonical)
        return doubleArrayOf(lngLat[0], lngLat[1])
    }

    // endregion

    companion object {
        private const val MIN_POINTS_SIZE = 100
        private const val MIN_LINE_POINTS_SIZE = 50

        /** An inclusive `[start, end]` index range into a point list. */
        private class IndexRange(val start: Int, val end: Int)

        private class DistPair(val dist: Double, val range1: IndexRange, val range2: IndexRange)

        // The queue is deliberately ordered so the pair with the *biggest* bbox distance pops
        // first, matching upstream's comparator.
        private val DIST_PAIR_COMPARATOR = Comparator<DistPair> { a, b -> b.dist.compareTo(a.dist) }

        private fun getRangeSize(range: IndexRange): Int = range.end - range.start + 1

        private fun isRangeSafe(range: IndexRange, threshold: Int): Boolean =
            range.end >= range.start && range.end < threshold

        private fun splitRange(range: IndexRange, isLine: Boolean): Pair<IndexRange?, IndexRange?> {
            if (range.start > range.end) return null to null
            val size = getRangeSize(range)
            if (isLine) {
                if (size == 2) return range to null
                val size1 = floor(size / 2.0).toInt()
                return IndexRange(range.start, range.start + size1) to
                        IndexRange(range.start + size1, range.end)
            }
            if (size == 1) return range to null
            val size1 = floor(size / 2.0).toInt() - 1
            return IndexRange(range.start, range.start + size1) to
                    IndexRange(range.start + size1 + 1, range.end)
        }

        private fun getBBox(coords: List<Coord>, range: IndexRange): BBox {
            if (!isRangeSafe(range, coords.size)) return newBBox()
            val bbox = newBBox()
            for (i in range.start..range.end) updateBBox(bbox, coords[i])
            return bbox
        }

        private fun getPolygonBBox(polygon: List<List<Coord>>): BBox {
            val bbox = newBBox()
            for (ring in polygon) for (coord in ring) updateBBox(bbox, coord)
            return bbox
        }

        private fun isValidBBox(bbox: BBox): Boolean =
            bbox[0] != Double.NEGATIVE_INFINITY && bbox[1] != Double.NEGATIVE_INFINITY &&
                    bbox[2] != Double.POSITIVE_INFINITY && bbox[3] != Double.POSITIVE_INFINITY

        /**
         * Distance between two bounding boxes: the x and y gaps are turned into a single fake
         * segment `(0,0) -> (dx,dy)` and measured with the ruler. Zero when the boxes overlap.
         */
        private fun bboxToBBoxDistance(bbox1: BBox, bbox2: BBox, ruler: CheapRuler): Double {
            if (!isValidBBox(bbox1) || !isValidBBox(bbox2)) return Double.NaN
            var dx = 0.0
            var dy = 0.0
            if (bbox1[2] < bbox2[0]) dx = bbox2[0] - bbox1[2]
            if (bbox1[0] > bbox2[2]) dx = bbox1[0] - bbox2[2]
            if (bbox1[1] > bbox2[3]) dy = bbox1[1] - bbox2[3]
            if (bbox1[3] < bbox2[1]) dy = bbox2[1] - bbox1[3]
            return ruler.distance(doubleArrayOf(0.0, 0.0), doubleArrayOf(dx, dy))
        }

        private fun pointToLineDistance(point: Coord, line: List<Coord>, ruler: CheapRuler): Double {
            val nearestPoint = ruler.pointOnLine(line, point)
            return ruler.distance(point, nearestPoint.point)
        }

        private fun segmentToSegmentDistance(
            p1: Coord, p2: Coord, q1: Coord, q2: Coord, ruler: CheapRuler,
        ): Double {
            val qLine = listOf(q1, q2)
            val pLine = listOf(p1, p2)
            val dist1 = min(pointToLineDistance(p1, qLine, ruler), pointToLineDistance(p2, qLine, ruler))
            val dist2 = min(pointToLineDistance(q1, pLine, ruler), pointToLineDistance(q2, pLine, ruler))
            return min(dist1, dist2)
        }

        private fun lineToLineDistance(
            line1: List<Coord>, range1: IndexRange,
            line2: List<Coord>, range2: IndexRange,
            ruler: CheapRuler,
        ): Double {
            if (!isRangeSafe(range1, line1.size) || !isRangeSafe(range2, line2.size)) {
                return Double.POSITIVE_INFINITY
            }

            var dist = Double.POSITIVE_INFINITY
            for (i in range1.start until range1.end) {
                val p1 = line1[i]
                val p2 = line1[i + 1]
                for (j in range2.start until range2.end) {
                    val q1 = line2[j]
                    val q2 = line2[j + 1]
                    if (segmentIntersectSegment(p1, p2, q1, q2)) return 0.0
                    dist = min(dist, segmentToSegmentDistance(p1, p2, q1, q2, ruler))
                }
            }
            return dist
        }

        private fun pointsToPointsDistance(
            points1: List<Coord>, range1: IndexRange,
            points2: List<Coord>, range2: IndexRange,
            ruler: CheapRuler,
        ): Double {
            if (!isRangeSafe(range1, points1.size) || !isRangeSafe(range2, points2.size)) {
                return Double.NaN
            }

            var dist = Double.POSITIVE_INFINITY
            for (i in range1.start..range1.end) {
                for (j in range2.start..range2.end) {
                    dist = min(dist, ruler.distance(points1[i], points2[j]))
                    if (dist == 0.0) return dist
                }
            }
            return dist
        }

        private fun pointToPolygonDistance(
            point: Coord, polygon: List<List<Coord>>, ruler: CheapRuler,
        ): Double {
            if (pointWithinPolygon(point, polygon, trueIfOnBoundary = true)) return 0.0
            var dist = Double.POSITIVE_INFINITY
            for (ring in polygon) {
                val front = ring.first()
                val back = ring.last()
                if (!front.contentEquals(back)) {
                    dist = min(dist, pointToLineDistance(point, listOf(back, front), ruler))
                    if (dist == 0.0) return dist
                }
                val nearestPoint = ruler.pointOnLine(ring, point)
                dist = min(dist, ruler.distance(point, nearestPoint.point))
                if (dist == 0.0) return dist
            }
            return dist
        }

        private fun lineToPolygonDistance(
            line: List<Coord>, range: IndexRange, polygon: List<List<Coord>>, ruler: CheapRuler,
        ): Double {
            if (!isRangeSafe(range, line.size)) return Double.NaN

            for (i in range.start..range.end) {
                if (pointWithinPolygon(line[i], polygon, trueIfOnBoundary = true)) return 0.0
            }

            var dist = Double.POSITIVE_INFINITY
            for (i in range.start until range.end) {
                val p1 = line[i]
                val p2 = line[i + 1]
                for (ring in polygon) {
                    var k = ring.size - 1
                    for (j in ring.indices) {
                        val q1 = ring[k]
                        val q2 = ring[j]
                        if (segmentIntersectSegment(p1, p2, q1, q2)) return 0.0
                        dist = min(dist, segmentToSegmentDistance(p1, p2, q1, q2, ruler))
                        k = j
                    }
                }
            }
            return dist
        }

        private fun polygonIntersect(poly1: List<List<Coord>>, poly2: List<List<Coord>>): Boolean {
            for (ring in poly1) {
                for (point in ring) {
                    if (pointWithinPolygon(point, poly2, trueIfOnBoundary = true)) return true
                }
            }
            return false
        }

        private fun polygonToPolygonDistance(
            polygon1: List<List<Coord>>,
            polygon2: List<List<Coord>>,
            ruler: CheapRuler,
            currentMiniDist: Double = Double.POSITIVE_INFINITY,
        ): Double {
            val bbox1 = getPolygonBBox(polygon1)
            val bbox2 = getPolygonBBox(polygon2)
            if (currentMiniDist != Double.POSITIVE_INFINITY &&
                bboxToBBoxDistance(bbox1, bbox2, ruler) >= currentMiniDist
            ) {
                return currentMiniDist
            }

            if (boxWithinBox(bbox1, bbox2)) {
                if (polygonIntersect(polygon1, polygon2)) return 0.0
            } else if (polygonIntersect(polygon2, polygon1)) {
                return 0.0
            }

            var dist = Double.POSITIVE_INFINITY
            for (ring1 in polygon1) {
                var l = ring1.size - 1
                for (i in ring1.indices) {
                    val p1 = ring1[l]
                    val p2 = ring1[i]
                    for (ring2 in polygon2) {
                        var k = ring2.size - 1
                        for (j in ring2.indices) {
                            val q1 = ring2[k]
                            val q2 = ring2[j]
                            if (segmentIntersectSegment(p1, p2, q1, q2)) return 0.0
                            dist = min(dist, segmentToSegmentDistance(p1, p2, q1, q2, ruler))
                            k = j
                        }
                    }
                    l = i
                }
            }
            return dist
        }

        private fun updateQueue(
            distQueue: TinyQueue<DistPair>,
            miniDist: Double,
            ruler: CheapRuler,
            points: List<Coord>,
            polyBBox: BBox,
            rangeA: IndexRange?,
        ) {
            if (rangeA == null) return
            val tempDist = bboxToBBoxDistance(getBBox(points, rangeA), polyBBox, ruler)
            if (tempDist < miniDist) {
                distQueue.push(DistPair(tempDist, rangeA, IndexRange(0, 0)))
            }
        }

        private fun updateQueueTwoSets(
            distQueue: TinyQueue<DistPair>,
            miniDist: Double,
            ruler: CheapRuler,
            pointSet1: List<Coord>,
            pointSet2: List<Coord>,
            range1: IndexRange?,
            range2: IndexRange?,
        ) {
            if (range1 == null || range2 == null) return
            val tempDist = bboxToBBoxDistance(
                getBBox(pointSet1, range1), getBBox(pointSet2, range2), ruler,
            )
            if (tempDist < miniDist) {
                distQueue.push(DistPair(tempDist, range1, range2))
            }
        }

        /** Divide and conquer: O(n log n) instead of the O(n²) brute force. */
        private fun pointsToPolygonDistance(
            points: List<Coord>,
            isLine: Boolean,
            polygon: List<List<Coord>>,
            ruler: CheapRuler,
            currentMiniDist: Double = Double.POSITIVE_INFINITY,
        ): Double {
            if (points.isEmpty() || polygon.isEmpty() || polygon[0].isEmpty()) return Double.NaN

            var miniDist = min(ruler.distance(points[0], polygon[0][0]), currentMiniDist)
            if (miniDist == 0.0) return miniDist

            val distQueue = TinyQueue(
                listOf(DistPair(0.0, IndexRange(0, points.size - 1), IndexRange(0, 0))),
                DIST_PAIR_COMPARATOR,
            )

            val polyBBox = getPolygonBBox(polygon)
            while (distQueue.size > 0) {
                val distPair = distQueue.pop() ?: break
                if (distPair.dist >= miniDist) continue

                val range = distPair.range1

                // For relatively small sets, brute force directly.
                val threshold = if (isLine) MIN_LINE_POINTS_SIZE else MIN_POINTS_SIZE
                if (getRangeSize(range) <= threshold) {
                    if (!isRangeSafe(range, points.size)) return Double.NaN
                    if (isLine) {
                        val tempDist = lineToPolygonDistance(points, range, polygon, ruler)
                        if (tempDist.isNaN() || tempDist == 0.0) return tempDist
                        miniDist = min(miniDist, tempDist)
                    } else {
                        for (i in range.start..range.end) {
                            val tempDist = pointToPolygonDistance(points[i], polygon, ruler)
                            miniDist = min(miniDist, tempDist)
                            if (miniDist == 0.0) return 0.0
                        }
                    }
                } else {
                    val (a, b) = splitRange(range, isLine)
                    updateQueue(distQueue, miniDist, ruler, points, polyBBox, a)
                    updateQueue(distQueue, miniDist, ruler, points, polyBBox, b)
                }
            }
            return miniDist
        }

        private fun pointSetToPointSetDistance(
            pointSet1: List<Coord>,
            isLine1: Boolean,
            pointSet2: List<Coord>,
            isLine2: Boolean,
            ruler: CheapRuler,
            currentMiniDist: Double = Double.POSITIVE_INFINITY,
        ): Double {
            if (pointSet1.isEmpty() || pointSet2.isEmpty()) return Double.NaN

            var miniDist = min(currentMiniDist, ruler.distance(pointSet1[0], pointSet2[0]))
            if (miniDist == 0.0) return miniDist

            val distQueue = TinyQueue(
                listOf(
                    DistPair(
                        0.0,
                        IndexRange(0, pointSet1.size - 1),
                        IndexRange(0, pointSet2.size - 1),
                    )
                ),
                DIST_PAIR_COMPARATOR,
            )

            while (distQueue.size > 0) {
                val distPair = distQueue.pop() ?: break
                if (distPair.dist >= miniDist) continue

                val rangeA = distPair.range1
                val rangeB = distPair.range2
                val threshold1 = if (isLine1) MIN_LINE_POINTS_SIZE else MIN_POINTS_SIZE
                val threshold2 = if (isLine2) MIN_LINE_POINTS_SIZE else MIN_POINTS_SIZE

                if (getRangeSize(rangeA) <= threshold1 && getRangeSize(rangeB) <= threshold2) {
                    if (!isRangeSafe(rangeA, pointSet1.size) && isRangeSafe(rangeB, pointSet2.size)) {
                        return Double.NaN
                    }
                    if (isLine1 && isLine2) {
                        miniDist = min(miniDist, lineToLineDistance(pointSet1, rangeA, pointSet2, rangeB, ruler))
                    } else if (isLine1 && !isLine2) {
                        val subline = pointSet1.subList(rangeA.start, rangeA.end + 1)
                        for (i in rangeB.start..rangeB.end) {
                            miniDist = min(miniDist, pointToLineDistance(pointSet2[i], subline, ruler))
                            if (miniDist == 0.0) return miniDist
                        }
                    } else if (!isLine1 && isLine2) {
                        val subline = pointSet2.subList(rangeB.start, rangeB.end + 1)
                        for (i in rangeA.start..rangeA.end) {
                            miniDist = min(miniDist, pointToLineDistance(pointSet1[i], subline, ruler))
                            if (miniDist == 0.0) return miniDist
                        }
                    } else {
                        miniDist = min(miniDist, pointsToPointsDistance(pointSet1, rangeA, pointSet2, rangeB, ruler))
                    }
                } else {
                    val (a1, a2) = splitRange(rangeA, isLine1)
                    val (b1, b2) = splitRange(rangeB, isLine2)
                    updateQueueTwoSets(distQueue, miniDist, ruler, pointSet1, pointSet2, a1, b1)
                    updateQueueTwoSets(distQueue, miniDist, ruler, pointSet1, pointSet2, a1, b2)
                    updateQueueTwoSets(distQueue, miniDist, ruler, pointSet1, pointSet2, a2, b1)
                    updateQueueTwoSets(distQueue, miniDist, ruler, pointSet1, pointSet2, a2, b2)
                }
            }
            return miniDist
        }

        // region GeoJSON decoding

        private fun asCoord(coordinates: Any?): Coord {
            val p = coordinates as? List<*> ?: return doubleArrayOf(0.0, 0.0)
            return doubleArrayOf(
                (p.getOrNull(0) as? Number)?.toDouble() ?: 0.0,
                (p.getOrNull(1) as? Number)?.toDouble() ?: 0.0,
            )
        }

        private fun asLine(coordinates: Any?): List<Coord> =
            (coordinates as? List<*>)?.map { asCoord(it) } ?: emptyList()

        private fun asPolygon(coordinates: Any?): List<List<Coord>> =
            (coordinates as? List<*>)?.map { asLine(it) } ?: emptyList()

        private fun toSimpleGeometry(geometry: Map<*, *>): List<SimpleGeometry> {
            val coordinates = geometry["coordinates"]
            return when (geometry["type"]) {
                "MultiPolygon" -> (coordinates as? List<*>).orEmpty().map { SimpleGeometry("Polygon", it) }
                "MultiLineString" -> (coordinates as? List<*>).orEmpty().map { SimpleGeometry("LineString", it) }
                "MultiPoint" -> (coordinates as? List<*>).orEmpty().map { SimpleGeometry("Point", it) }
                else -> listOf(SimpleGeometry(geometry["type"] as? String ?: "", coordinates))
            }
        }

        // endregion

        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error(
                    "'distance' expression requires exactly one argument, but found ${args.size - 1} instead."
                )
            }
            if (isValue(args[1])) {
                val geojson = args[1] as? Map<*, *>
                if (geojson != null) {
                    when (geojson["type"]) {
                        "FeatureCollection" -> {
                            val features = (geojson["features"] as? List<*>).orEmpty()
                            val geometries = features.flatMap { feature ->
                                val geometry = (feature as? Map<*, *>)?.get("geometry") as? Map<*, *>
                                geometry?.let { toSimpleGeometry(it) } ?: emptyList()
                            }
                            return Distance(geojson, geometries)
                        }

                        "Feature" -> {
                            val geometry = geojson["geometry"] as? Map<*, *>
                            if (geometry != null) return Distance(geojson, toSimpleGeometry(geometry))
                        }

                        else -> if (geojson.containsKey("type") && geojson.containsKey("coordinates")) {
                            return Distance(geojson, toSimpleGeometry(geojson))
                        }
                    }
                }
            }
            return context.error(
                "'distance' expression requires valid geojson object that contains polygon geometry type."
            )
        }
    }
}
