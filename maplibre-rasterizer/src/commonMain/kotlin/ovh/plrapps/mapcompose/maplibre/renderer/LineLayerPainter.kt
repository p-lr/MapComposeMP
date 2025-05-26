package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.LineLayer
import ovh.plrapps.mapcompose.maplibre.spec.style.line.LineLayout
import ovh.plrapps.mapcompose.maplibre.spec.style.line.LinePaint
import ovh.plrapps.mapcompose.maplibre.spec.style.props.Expr

class LineLayerPainter : BaseLayerPainter<LineLayer>() {
    override fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: LineLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double
    ) {
        if (feature.type != Tile.GeomType.LINESTRING && feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint ?: LinePaint()
        val layout = style.layout

        val lineColor =
            paint.lineColor?.process(featureProperties = featureProperties, zoom = actualZoom) ?: Color.Black
        val lineOpacity = paint.lineOpacity?.process(featureProperties, actualZoom)?.toFloat() ?: 1f
        val lineWidth = paint.lineWidth?.process(featureProperties, actualZoom)?.toFloat()?.let { it*canvas.density } ?: 1f
        val lineGapWidth = paint.lineGapWidth?.process(featureProperties, actualZoom)?.toFloat() ?: 0f
        val lineBlur = paint.lineBlur?.process(featureProperties, actualZoom)?.toFloat() ?: 0f
        val lineOffset = paint.lineOffset?.process(featureProperties, actualZoom)?.toFloat() ?: 0f
        val lineTranslate =
            paint.lineTranslate?.process(featureProperties, actualZoom)?.map { it.toFloat() } ?: listOf(0f, 0f)
        val lineTranslateAnchor = paint.lineTranslateAnchor?.process(featureProperties, actualZoom) ?: "map"
        val lineCap = getLineCap(layout, actualZoom, featureProperties)
        val lineJoin = getLineJoin(layout, actualZoom, featureProperties)
        val lineMiterLimit = layout?.lineMiterLimit?.process(featureProperties, actualZoom)?.toFloat() ?: 2f
        val lineRoundLimit = layout?.lineRoundLimit?.process(featureProperties, actualZoom) ?: 1.0
        val dashArray = getLineDashArray(paint, actualZoom, featureProperties)

        // TODO rewrite
        val lineWidthPx = lineWidth
        val lineGapWidthPx = lineGapWidth
        val lineBlurPx = lineBlur
        val lineOffsetPx = lineOffset
        val dashArrayPx = dashArray?.map { it.toFloat() * canvas.density }?.toFloatArray()

        if (feature.type == Tile.GeomType.POLYGON) {
            val polygons = geometryDecoders.decodePolygons(feature.geometry, canvasSize = canvasSize, extent = extent)
            // println("[LineLayerPainter] POLYGON/MULTIPOLYGON: polygons.size = ${polygons.size}")
            for ((polyIdx, rings) in polygons.withIndex()) {
                for ((ringIdx, ring) in rings.withIndex()) {
                    if (ring.isNotEmpty()) {
                        // println("[LineLayerPainter] Polygon #$polyIdx, ring #$ringIdx: size = ${ring.size}")
                        val path = createPathFromPoints(ring, lineOffsetPx)
                        if (lineGapWidthPx > 0) {
                            // First, draw a gap (a wide line in the base color)
                            drawPathWithBlur(
                                canvas = canvas,
                                path = path,
                                color = Color.Black, // TODO
                                opacity = 1f,
                                width = lineWidthPx + lineGapWidthPx * 2,
                                blur = lineBlurPx,
                                translate = lineTranslate,
                                translateAnchor = lineTranslateAnchor,
                                cap = lineCap,
                                join = lineJoin,
                                miterLimit = lineMiterLimit,
                                roundLimit = lineRoundLimit,
                                dashArray = dashArrayPx
                            )

                            drawPathWithBlur(
                                canvas = canvas,
                                path = path,
                                color = lineColor,
                                opacity = lineOpacity,
                                width = lineWidthPx,
                                blur = lineBlurPx,
                                translate = lineTranslate,
                                translateAnchor = lineTranslateAnchor,
                                cap = lineCap,
                                join = lineJoin,
                                miterLimit = lineMiterLimit,
                                roundLimit = lineRoundLimit,
                                dashArray = dashArrayPx
                            )
                        } else {
                            drawPathWithBlur(
                                canvas = canvas,
                                path = path,
                                color = lineColor,
                                opacity = lineOpacity,
                                width = lineWidthPx,
                                blur = lineBlurPx,
                                translate = lineTranslate,
                                translateAnchor = lineTranslateAnchor,
                                cap = lineCap,
                                join = lineJoin,
                                miterLimit = lineMiterLimit,
                                roundLimit = lineRoundLimit,
                                dashArray = dashArrayPx
                            )
                        }
                    }
                }
            }
        } else {
            val path = createPath(feature, canvasSize, extent, lineOffsetPx) ?: return
            drawPathWithBlur(
                canvas = canvas,
                path = path,
                color = lineColor,
                opacity = lineOpacity,
                width = lineWidthPx,
                blur = lineBlurPx,
                translate = lineTranslate,
                translateAnchor = lineTranslateAnchor,
                cap = lineCap,
                join = lineJoin,
                miterLimit = lineMiterLimit,
                roundLimit = lineRoundLimit,
                dashArray = dashArrayPx
            )
        }

        if (style.id == "geolines") {
            println("DEBUG!")
        }
    }

    private fun drawPathWithBlur(
        canvas: DrawScope,
        path: Path,
        color: Color,
        opacity: Float,
        width: Float,
        blur: Float,
        translate: List<Float>,
        translateAnchor: String,
        cap: StrokeCap,
        join: StrokeJoin,
        miterLimit: Float,
        roundLimit: Double,
        dashArray: FloatArray?
    ) {
        val (dx, dy) = when (translateAnchor) {
            "viewport" -> translate
            else -> translate // "map"
        }

        canvas.translate(dx, dy) {
            if (blur > 0) {
                val blurSteps = 5
                val blurStep = blur / blurSteps
                val opacityStep = opacity / blurSteps

                for (i in 0 until blurSteps) {
                    val currentBlur = blurStep * (i + 1)
                    val currentOpacity = opacityStep * (i + 1)
                    val currentWidth = width + currentBlur * 2

                    canvas.drawPath(
                        path = path,
                        color = color.copy(alpha = currentOpacity),
                        style = Stroke(
                            width = currentWidth,
                            cap = cap,
                            join = if (join == StrokeJoin.Round && width <= roundLimit) StrokeJoin.Round else StrokeJoin.Miter,
                            miter = miterLimit,
                            pathEffect = dashArray?.let { createDashPathEffect(it) }
                        )
                    )
                }
            } else {
                canvas.drawPath(
                    path = path,
                    color = color.copy(alpha = opacity),
                    style = Stroke(
                        width = width,
                        cap = cap,
                        join = if (join == StrokeJoin.Round && width <= roundLimit) StrokeJoin.Round else StrokeJoin.Miter,
                        miter = miterLimit,
                        pathEffect = dashArray?.let { createDashPathEffect(it) }
                    )
                )
            }
        }
    }

    private fun createPathFromPoints(points: List<Pair<Float, Float>>, offset: Float): Path {
        if (offset == 0f) {
            val path = Path()
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

        val path = Path()
        var isFirst = true
        var prevPoint: Pair<Float, Float>? = null
        var prevNormal: Pair<Float, Float>? = null

        for (i in points.indices) {
            val currentPoint = points[i]
            val nextPoint = if (i < points.size - 1) points[i + 1] else null

            if (isFirst) {
                path.moveTo(currentPoint.first, currentPoint.second)
                isFirst = false
            } else {
                // Calculate the normal for the current segment
                val normal = if (nextPoint != null) {
                    val dx = nextPoint.first - currentPoint.first
                    val dy = nextPoint.second - currentPoint.second
                    val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (length > 0) {
                        Pair(-dy / length, dx / length)
                    } else {
                        Pair(0f, 0f)
                    }
                } else {
                    prevNormal ?: Pair(0f, 0f)
                }

                val offsetPoint = Pair(
                    currentPoint.first + normal.first * offset,
                    currentPoint.second + normal.second * offset
                )

                path.lineTo(offsetPoint.first, offsetPoint.second)
                prevNormal = normal
            }
            prevPoint = currentPoint
        }

        return path
    }

    private fun createPath(feature: Tile.Feature, canvasSize: Int, extent: Int, offset: Float): Path? {
        return when (feature.type) {
            Tile.GeomType.LINESTRING -> {
                val lines = geometryDecoders.decodeLine(feature.geometry, canvasSize = canvasSize, extent = extent)
                if (lines.isNotEmpty()) {
                    val path = Path()
                    for (line in lines) {
                        if (line.isNotEmpty()) {
                            var isFirst = true
                            for (point in line) {
                                if (isFirst) {
                                    path.moveTo(point.first, point.second)
                                    isFirst = false
                                } else {
                                    path.lineTo(point.first, point.second)
                                }
                            }
                        }
                    }
                    path
                } else null
            }

            else -> super.createPath(feature, canvasSize, extent)
        }
    }

    private fun createDashPathEffect(dashArray: FloatArray): PathEffect {
        return PathEffect.dashPathEffect(
            intervals = dashArray,
            phase = 0f
        )
    }

    private fun getLineCap(layout: LineLayout?, actualZoom: Double, featureProperties: Map<String, Any?>?): StrokeCap {
        return when (layout?.lineCap?.process(featureProperties = featureProperties, zoom = actualZoom) ?: "butt") {
            "butt" -> StrokeCap.Butt
            "round" -> StrokeCap.Round
            "square" -> StrokeCap.Square
            else -> StrokeCap.Butt
        }
    }

    private fun getLineJoin(
        layout: LineLayout?,
        actualZoom: Double,
        featureProperties: Map<String, Any?>?
    ): StrokeJoin {
        return when (layout?.lineJoin?.process(featureProperties = featureProperties, zoom = actualZoom) ?: "miter") {
            "bevel" -> StrokeJoin.Bevel
            "round" -> StrokeJoin.Round
            "miter" -> StrokeJoin.Miter
            else -> StrokeJoin.Miter
        }
    }

    private fun getLineDashArray(
        paint: LinePaint,
        actualZoom: Double,
        featureProperties: Map<String, Any?>?
    ): DoubleArray? {
        return paint.lineDasharray?.process(featureProperties = featureProperties, zoom = actualZoom)?.toDoubleArray()
    }
} 