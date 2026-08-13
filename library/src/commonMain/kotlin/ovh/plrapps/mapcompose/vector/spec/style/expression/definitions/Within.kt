package ovh.plrapps.mapcompose.vector.spec.style.expression.definitions

import ovh.plrapps.mapcompose.vector.spec.style.expression.BooleanType
import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvaluationContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.ExprType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Expression
import ovh.plrapps.mapcompose.vector.spec.style.expression.ParsingContext
import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.BBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.Coord
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.EXTENT
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.boxWithinBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.getTileCoordinates
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.lineStringWithinPolygons
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.newBBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.pointWithinPolygons
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.updateBBox
import ovh.plrapps.mapcompose.vector.spec.style.expression.isValue

/**
 * Ported from `maplibre-style-spec/src/expression/definitions/within.ts`.
 *
 * The GeoJSON argument is decoded once at parse time into `[lng, lat]` rings. At evaluation the
 * rings are projected into *global* tile coordinates (`tileX * EXTENT + localX`) so they can be
 * compared directly against MVT feature geometry. `Polygon` inputs are normalized to a
 * single-element multipolygon, which gives identical results to upstream's separate code path.
 */
class Within(val geojson: Any?, private val polygons: List<List<List<List<Double>>>>) : Expression {

    override val type: ExprType = BooleanType

    override fun evaluate(ctx: EvaluationContext): Any {
        val canonical = ctx.canonicalID() ?: return false
        val geometry = ctx.geometry()
        if (geometry.isEmpty()) return false

        return when (ctx.geometryType()) {
            "Point" -> pointsWithinPolygons(geometry, canonical)
            "LineString" -> linesWithinPolygons(geometry, canonical)
            else -> false
        }
    }

    private fun pointsWithinPolygons(geometry: List<List<Point2D>>, canonical: CanonicalTileId): Boolean {
        val pointBBox = newBBox()
        val polyBBox = newBBox()

        val tilePolygons = getTilePolygons(polyBBox, canonical)
        val tilePoints = getTilePoints(geometry, pointBBox, polyBBox, canonical)
        if (!boxWithinBox(pointBBox, polyBBox)) return false

        return tilePoints.all { pointWithinPolygons(it, tilePolygons) }
    }

    private fun linesWithinPolygons(geometry: List<List<Point2D>>, canonical: CanonicalTileId): Boolean {
        val lineBBox = newBBox()
        val polyBBox = newBBox()

        val tilePolygons = getTilePolygons(polyBBox, canonical)
        val tileLines = getTileLines(geometry, lineBBox, polyBBox, canonical)
        if (!boxWithinBox(lineBBox, polyBBox)) return false

        return tileLines.all { lineStringWithinPolygons(it, tilePolygons) }
    }

    private fun getTilePolygons(bbox: BBox, canonical: CanonicalTileId): List<List<List<Coord>>> =
        polygons.map { polygon ->
            polygon.map { ring ->
                ring.map { position ->
                    val coord = getTileCoordinates(position, canonical)
                    updateBBox(bbox, coord)
                    coord
                }
            }
        }

    private fun getTilePoints(
        geometry: List<List<Point2D>>,
        pointBBox: BBox,
        polyBBox: BBox,
        canonical: CanonicalTileId,
    ): List<Coord> {
        val worldSize = (1 shl canonical.z).toDouble() * EXTENT
        val shiftX = canonical.x.toDouble() * EXTENT
        val shiftY = canonical.y.toDouble() * EXTENT
        val tilePoints = mutableListOf<Coord>()
        for (points in geometry) {
            for (point in points) {
                val p = doubleArrayOf(point.x + shiftX, point.y + shiftY)
                updatePoint(p, pointBBox, polyBBox, worldSize)
                tilePoints.add(p)
            }
        }
        return tilePoints
    }

    private fun getTileLines(
        geometry: List<List<Point2D>>,
        lineBBox: BBox,
        polyBBox: BBox,
        canonical: CanonicalTileId,
    ): List<List<Coord>> {
        val worldSize = (1 shl canonical.z).toDouble() * EXTENT
        val shiftX = canonical.x.toDouble() * EXTENT
        val shiftY = canonical.y.toDouble() * EXTENT
        val tileLines = mutableListOf<List<Coord>>()
        for (line in geometry) {
            val tileLine = mutableListOf<Coord>()
            for (point in line) {
                val p = doubleArrayOf(point.x + shiftX, point.y + shiftY)
                updateBBox(lineBBox, p)
                tileLine.add(p)
            }
            tileLines.add(tileLine)
        }
        if (lineBBox[2] - lineBBox[0] <= worldSize / 2) {
            resetBBox(lineBBox)
            for (line in tileLines) {
                for (p in line) updatePoint(p, lineBBox, polyBBox, worldSize)
            }
        }
        return tileLines
    }

    /** Shifts a point across the antimeridian when that brings it closer to the polygon. */
    private fun updatePoint(p: Coord, bbox: BBox, polyBBox: BBox, worldSize: Double) {
        if (p[0] < polyBBox[0] || p[0] > polyBBox[2]) {
            val halfWorldSize = worldSize * 0.5
            var shift = when {
                p[0] - polyBBox[0] > halfWorldSize -> -worldSize
                polyBBox[0] - p[0] > halfWorldSize -> worldSize
                else -> 0.0
            }
            if (shift == 0.0) {
                shift = when {
                    p[0] - polyBBox[2] > halfWorldSize -> -worldSize
                    polyBBox[2] - p[0] > halfWorldSize -> worldSize
                    else -> 0.0
                }
            }
            p[0] += shift
        }
        updateBBox(bbox, p)
    }

    private fun resetBBox(bbox: BBox) {
        bbox[0] = Double.POSITIVE_INFINITY
        bbox[1] = Double.POSITIVE_INFINITY
        bbox[2] = Double.NEGATIVE_INFINITY
        bbox[3] = Double.NEGATIVE_INFINITY
    }

    override fun eachChild(fn: (Expression) -> Unit) = Unit

    override fun outputDefined(): Boolean = true

    companion object {
        fun parse(args: List<Any?>, context: ParsingContext): Expression? {
            if (args.size != 2) {
                return context.error(
                    "'within' expression requires exactly one argument, but found ${args.size - 1} instead."
                )
            }
            if (isValue(args[1])) {
                val geojson = args[1] as? Map<*, *>
                val polygons = geojson?.let { extractPolygons(it) }
                if (polygons != null && polygons.isNotEmpty()) {
                    return Within(geojson, polygons)
                }
            }
            return context.error(
                "'within' expression requires valid geojson object that contains polygon geometry type."
            )
        }

        /** Normalizes Polygon / MultiPolygon / Feature / FeatureCollection to a list of polygons. */
        private fun extractPolygons(geojson: Map<*, *>): List<List<List<List<Double>>>>? {
            return when (geojson["type"]) {
                "FeatureCollection" -> {
                    val out = mutableListOf<List<List<List<Double>>>>()
                    val features = geojson["features"] as? List<*> ?: return null
                    for (feature in features) {
                        val geometry = (feature as? Map<*, *>)?.get("geometry") as? Map<*, *> ?: continue
                        out += extractPolygons(geometry) ?: continue
                    }
                    out.ifEmpty { null }
                }

                "Feature" -> (geojson["geometry"] as? Map<*, *>)?.let { extractPolygons(it) }

                "Polygon" -> coordsToPolygon(geojson["coordinates"])?.let { listOf(it) }

                "MultiPolygon" -> {
                    val coords = geojson["coordinates"] as? List<*> ?: return null
                    coords.mapNotNull { coordsToPolygon(it) }.ifEmpty { null }
                }

                else -> null
            }
        }

        private fun coordsToPolygon(coords: Any?): List<List<List<Double>>>? {
            val rings = coords as? List<*> ?: return null
            return rings.map { ring ->
                (ring as? List<*> ?: return null).map { position ->
                    val p = position as? List<*> ?: return null
                    p.map { (it as? Number)?.toDouble() ?: return null }
                }
            }
        }
    }
}
