package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.classifyRings

data class Point(val x: Double, val y: Double)

/**
 * Decodes MVT geometry command streams into canvas-space geometry.
 *
 * The command encoding is the one in the Mapbox Vector Tile spec: each command integer packs an id
 * in its low 3 bits (1 = MoveTo, 2 = LineTo, 7 = ClosePath) and a repeat count in the rest, and
 * each parameter is a zig-zag encoded delta from the running cursor.
 *
 * Everything here scales to the destination tile bitmap as it decodes, so the points a painter
 * receives are already canvas pixels in `0..canvasSize`. The unscaled `0..extent` tile space that
 * `within` / `distance` expressions are defined in is decoded separately, by
 * `BaseRenderer.decodeRawGeometry`.
 */
class GeometryDecoders {
    companion object {
        internal fun decodeZigZag(n: Int): Int = (n ushr 1) xor (-(n and 1))
        internal fun tileCoordToCanvas(
            x: Int,
            y: Int,
            canvasSize: Int,
            extent: Int
        ): Pair<Float, Float> {
            val scale = canvasSize.toFloat() / extent
            val result = Pair(x * scale, y * scale)
            return result
        }
    }

    /**
     * All rings of a polygon feature, in canvas space, exterior and interior mixed together in tile
     * order. Use [decodePolygons] when the exterior/hole grouping matters.
     *
     * A ring is emitted both on ClosePath and when a MoveTo starts the next one, so a tile that
     * omits the final ClosePath does not silently lose its last ring.
     */
    fun decodePolygon(
        geometry: List<Int>,
        extent: Int,
        canvasSize: Int
    ): List<List<Pair<Float, Float>>> {
        val scale = canvasSize.toFloat() / extent
        return decodePolygonRings(geometry).map { ring ->
            ring.map { Pair((it.x * scale).toFloat(), (it.y * scale).toFloat()) }
        }
    }

    /**
     * Rings of a polygon feature in raw `0..extent` tile space, closed and in tile order.
     *
     * Kept unscaled because [classifyRings] groups by signed area sign, and scaling first would
     * only add float error to that test.
     */
    private fun decodePolygonRings(geometry: List<Int>): List<List<Point2D>> {
        val rings = mutableListOf<List<Point2D>>()
        var x = 0
        var y = 0
        var i = 0
        var currentRing: MutableList<Point2D>? = null

        fun flush() {
            val ring = currentRing
            if (ring != null && ring.isNotEmpty()) {
                if (ring.first() != ring.last()) ring.add(ring.first())
                rings.add(ring)
            }
            currentRing = null
        }

        while (i < geometry.size) {
            val cmdInteger = geometry[i++]
            val command = cmdInteger and 0x7
            val count = cmdInteger shr 3
            when (command) {
                1 -> { // MoveTo -- starts a new ring
                    if (count < 1) continue
                    flush()
                    currentRing = mutableListOf()
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        x += decodeZigZag(geometry[i++])
                        y += decodeZigZag(geometry[i++])
                        currentRing?.add(Point2D(x.toDouble(), y.toDouble()))
                    }
                }

                2 -> { // LineTo
                    if (currentRing == null) break
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        x += decodeZigZag(geometry[i++])
                        y += decodeZigZag(geometry[i++])
                        currentRing?.add(Point2D(x.toDouble(), y.toDouble()))
                    }
                }

                7 -> flush() // ClosePath
                else -> break
            }
        }
        flush()
        return rings
    }

    fun decodeLine(
        geometry: List<Int>,
        extent: Int,
        canvasSize: Int
    ): List<List<Pair<Float, Float>>> {
        val lines = mutableListOf<MutableList<Pair<Float, Float>>>()
        var x = 0
        var y = 0
        var i = 0
        var currentLine: MutableList<Pair<Float, Float>>? = null
        val scale = canvasSize.toFloat() / extent
        while (i < geometry.size) {
            val cmdInteger = geometry[i++]
            val command = cmdInteger and 0x7
            val count = cmdInteger shr 3
            when (command) {
                1 -> { // MoveTo
                    if (count < 1) continue
                    if (currentLine != null && currentLine.isNotEmpty()) {
                        lines.add(currentLine)
                    }
                    currentLine = mutableListOf()
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        x += decodeZigZag(geometry[i++])
                        y += decodeZigZag(geometry[i++])
                        currentLine.add(Pair(x * scale, y * scale))
                    }
                }
                2 -> { // LineTo
                    if (currentLine == null) break
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        x += decodeZigZag(geometry[i++])
                        y += decodeZigZag(geometry[i++])
                        currentLine.add(Pair(x * scale, y * scale))
                    }
                }
                7 -> {
                    // For lines ClosePath is ignored
                }
                else -> break
            }
        }
        if (currentLine != null && currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        return lines
    }

    fun createLineStringPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        
        var isFirst = true
        for (point in points) {
            if (isFirst) {
                path.moveTo(point.first, point.second)
                isFirst = false
            } else {
                path.lineTo(point.first, point.second)
            }
        }
        return path
    }

    /**
     * Builds one fillable [Path] from a polygon's rings.
     *
     * The fill type is [PathFillType.NonZero], and that is what cuts the holes: MVT winds interior
     * rings opposite to their exterior, so the non-zero rule leaves their interiors unfilled
     * without the renderer having to identify them.
     */
    fun createPolygonPath(rings: List<List<Pair<Float, Float>>>): Path {
        val path = Path()
        path.fillType = PathFillType.NonZero
        for (ring in rings) {
            if (ring.isEmpty()) continue
            
            var isFirst = true
            for (point in ring) {
                if (isFirst) {
                    path.moveTo(point.first, point.second)
                    isFirst = false
                } else {
                    path.lineTo(point.first, point.second)
                }
            }
            path.close()
        }
        return path
    }

    fun calculateCentroid(points: List<Point>): Point {
        var sumX = 0.0
        var sumY = 0.0
        for (point in points) {
            sumX += point.x
            sumY += point.y
        }
        return Point(sumX / points.size, sumY / points.size)
    }

    fun calculateCentroid(path: Path): Point {
        val bounds = path.getBounds()
        return Point((bounds.left + bounds.width / 2).toDouble(), (bounds.top + bounds.height / 2f).toDouble())
    }

    fun decodePoint(
        geometry: List<Int>,
        extent: Int = 4096,
        canvasSize: Int = 256
    ): List<Point> {
        val points = mutableListOf<Point>()
        var x = 0
        var y = 0
        var i = 0
        while (i < geometry.size) {
            if (i >= geometry.size) break
            val cmdInteger = geometry[i++]
            val command = cmdInteger and 0x7
            val count = cmdInteger shr 3
            when (command) {
                1 -> { // MoveTo
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        val dx = geometry[i++]
                        val dy = geometry[i++]
                        x += decodeZigZag(dx)
                        y += decodeZigZag(dy)
                        val point = tileCoordToCanvas(x = x, y = y, canvasSize = canvasSize, extent = extent)
                        points.add(Point(point.first.toDouble(), point.second.toDouble()))
                    }
                }
                else -> break
            }
        }
        return points
    }

    /**
     * Every vertex of a feature, whatever its geometry type.
     *
     * This is what upstream's `CircleBucket.addFeature` walks: it does not look at the geometry
     * type at all, so a `circle` layer over a line or polygon source draws a circle at each of its
     * vertices. [decodePoint] reads only `MoveTo`, which is right for a point feature but stops at
     * the first vertex of anything else.
     */
    fun decodeVertices(
        geometry: List<Int>,
        extent: Int = 4096,
        canvasSize: Int = 256
    ): List<Point> {
        val points = mutableListOf<Point>()
        var x = 0
        var y = 0
        var i = 0
        while (i < geometry.size) {
            val cmdInteger = geometry[i++]
            val command = cmdInteger and 0x7
            val count = cmdInteger shr 3
            when (command) {
                1, 2 -> { // MoveTo, LineTo
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        x += decodeZigZag(geometry[i++])
                        y += decodeZigZag(geometry[i++])
                        val point = tileCoordToCanvas(x = x, y = y, canvasSize = canvasSize, extent = extent)
                        points.add(Point(point.first.toDouble(), point.second.toDouble()))
                    }
                }

                7 -> Unit // ClosePath repeats the ring's first vertex, which is already listed.
                else -> break
            }
        }
        return points
    }

    /**
     * Groups a polygon feature's rings into polygons, each an exterior ring followed by its holes.
     *
     * A single MVT feature may hold a MultiPolygon, distinguished only by winding order: an
     * exterior ring is wound one way and its holes the other. Grouping is delegated to
     * [classifyRings], the port of upstream's `classify_rings.ts`, so the two agree on the
     * degenerate cases (zero-area rings, a leading hole).
     */
    fun decodePolygons(
        geometry: List<Int>,
        extent: Int,
        canvasSize: Int
    ): List<List<List<Pair<Float, Float>>>> {
        val scale = canvasSize.toFloat() / extent
        return classifyRings(decodePolygonRings(geometry)).map { polygon ->
            polygon.map { ring ->
                ring.map { Pair((it.x * scale).toFloat(), (it.y * scale).toFloat()) }
            }
        }
    }
}
