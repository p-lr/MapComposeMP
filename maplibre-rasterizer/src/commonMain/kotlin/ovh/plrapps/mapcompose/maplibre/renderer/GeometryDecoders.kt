package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.Path

data class Point(val x: Double, val y: Double)

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

    fun decodePolygon(
        geometry: List<Int>,
        extent: Int,
        canvasSize: Int
    ): List<List<Pair<Float, Float>>> {
        val rings = mutableListOf<MutableList<Pair<Float, Float>>>()
        var x = 0
        var y = 0
        var i = 0
        var currentRing: MutableList<Pair<Float, Float>>? = null
        while (i < geometry.size) {
            if (i >= geometry.size) break
            val cmdInteger = geometry[i++]
            val command = cmdInteger and 0x7
            val count = cmdInteger shr 3
            when (command) {
                1 -> { // MoveTo
                    if (count < 1) continue // According to the MoveTo specification there must be at least one
                    currentRing = mutableListOf()
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        val dx = geometry[i++]
                        val dy = geometry[i++]
                        x += decodeZigZag(dx)
                        y += decodeZigZag(dy)
                        val point = tileCoordToCanvas(x, y, canvasSize, extent)
                        currentRing.add(point)
                    }
                }
                2 -> { // LineTo
                    if (currentRing == null) break
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        val dx = geometry[i++]
                        val dy = geometry[i++]
                        x += decodeZigZag(dx)
                        y += decodeZigZag(dy)
                        val point = tileCoordToCanvas(x, y, canvasSize, extent)
                        currentRing.add(point)
                    }
                }
                7 -> { // ClosePath
                    if (currentRing != null && currentRing.isNotEmpty()) {
                        // close ring
                        currentRing.add(currentRing[0])
                        rings.add(currentRing)
                        currentRing = null
                    }
                }
                else -> break // unknown command
            }
        }
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
        while (i < geometry.size) {
            if (i >= geometry.size) break
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
                        val dx = geometry[i++]
                        val dy = geometry[i++]
                        x += decodeZigZag(dx)
                        y += decodeZigZag(dy)
                        val point = tileCoordToCanvas(x, y, canvasSize, extent)
                        currentLine.add(point)
                    }
                }
                2 -> { // LineTo
                    if (currentLine == null) break
                    for (j in 0 until count) {
                        if (i + 1 >= geometry.size) break
                        val dx = geometry[i++]
                        val dy = geometry[i++]
                        x += decodeZigZag(dx)
                        y += decodeZigZag(dy)
                        val point = tileCoordToCanvas(x, y, canvasSize, extent)
                        currentLine.add(point)
                    }
                }
                7 -> { // ClosePath
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
    
    fun createPolygonPath(rings: List<List<Pair<Float, Float>>>): Path {
        val path = Path()
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
     * Returns a list of polygons (each a list of rings).
     * For Polygon, a list of one element.
     * For MultiPolygon, a list of all polygons.
     */
    fun decodePolygons(
        geometry: List<Int>,
        extent: Int,
        canvasSize: Int
    ): List<List<List<Pair<Float, Float>>>> {
        return listOf(decodePolygon(geometry, extent, canvasSize))
    }
} 