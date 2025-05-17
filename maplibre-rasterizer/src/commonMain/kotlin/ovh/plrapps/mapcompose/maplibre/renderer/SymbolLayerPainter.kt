package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer
import kotlinx.serialization.json.*
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.renderer.collision.LabelPlacement

class SymbolLayerPainter(
    private val textMeasurer: TextMeasurer,
) : BaseLayerPainter<SymbolLayer>() {


    private fun substituteTemplate(template: String, properties: Map<String, Any?>?): String {
        if (properties == null) return template
        return Regex("\\{([^}]+)\\}").replace(template) { matchResult ->
            val key = matchResult.groupValues[1]
            properties[key]?.toString() ?: ""
        }
    }

    override fun paint(
        canvas: DrawScope,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: SymbolLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?
    ) {
        if(style.id == "countries-label") {
            println("DEBUG")
        }
        val paint = style.paint ?: return
        val layout = style.layout ?: return

        val symbolPlacement = layout.symbolPlacement?.process(featureProperties, zoom) ?: "point"
        val templateTextField = layout.textField?.process(featureProperties, zoom) as? String ?: return
        val textField = substituteTemplate(templateTextField, featureProperties)
        val fontSize = layout.textSize?.process(featureProperties, zoom)?.toFloat() ?: 16f
        val textColor = paint.textColor?.process(featureProperties, zoom) ?: Color.Black
        val textOpacity = paint.textOpacity?.process(featureProperties, zoom)?.toFloat() ?: 1f
        val textHaloColor = paint.textHaloColor?.process(featureProperties, zoom) ?: Color.Black
        val textHaloWidth = paint.textHaloWidth?.process(featureProperties, zoom)?.toFloat() ?: 0f
        val offsetArr = (layout.textOffset?.process(featureProperties, zoom) as? List<Double>) ?: listOf(0.0, 0.0)
        val anchor = layout.textAnchor?.process(featureProperties, zoom) ?: "center"

        val dx = (offsetArr.getOrNull(0) ?: 0.0).toFloat() * fontSize
        val dy = (offsetArr.getOrNull(1) ?: 0.0).toFloat() * fontSize
        var anchorDx = 0f
        var anchorDy = 0f

        // Define a point for the text
        val positionAndAngle: Pair<Pair<Float, Float>, Float>? = when {
            feature.type == Tile.GeomType.POINT -> {
                val points = geometryDecoders.decodePoint(feature.geometry, extent, canvasSize)
                points.firstOrNull()?.let { (it.x.toFloat() to it.y.toFloat()) to 0f }
            }
            symbolPlacement == "line" && feature.type == Tile.GeomType.LINESTRING -> {
                val points = geometryDecoders.decodeLine(feature.geometry, canvasSize = canvasSize, extent = extent)
                if (points.size >= 2) {
                    // 1. Find the point at the midpoint of the line length
                    var totalLength = 0f
                    for (i in 1 until points.size) {
                        val dxL = points[i].first - points[i-1].first
                        val dyL = points[i].second - points[i-1].second
                        totalLength += kotlin.math.sqrt(dxL * dxL + dyL * dyL)
                    }
                    var accLength = 0f
                    var midPoint: Pair<Float, Float>? = null
                    var angle = 0f
                    for (i in 1 until points.size) {
                        val dxL = points[i].first - points[i-1].first
                        val dyL = points[i].second - points[i-1].second
                        val segLen = kotlin.math.sqrt(dxL * dxL + dyL * dyL)
                        if (accLength + segLen >= totalLength / 2) {
                            val t = if (segLen == 0f) 0f else (totalLength / 2 - accLength) / segLen
                            val x = points[i-1].first + t * dxL
                            val y = points[i-1].second + t * dyL
                            midPoint = x to y
                            angle = kotlin.math.atan2(dyL, dxL) * 180f / kotlin.math.PI.toFloat()
                            break
                        }
                        accLength += segLen
                    }
                    if (midPoint != null) midPoint to angle else null
                } else null
            }
            symbolPlacement == "line" && feature.type == Tile.GeomType.POLYGON -> {
                val polygons = geometryDecoders.decodePolygons(feature.geometry, canvasSize = canvasSize, extent = extent)
                val firstRing = polygons.firstOrNull()?.firstOrNull()
                if (firstRing != null && firstRing.size >= 2) {
                    var totalLength = 0f
                    for (i in 1 until firstRing.size) {
                        val dxL = firstRing[i].first - firstRing[i-1].first
                        val dyL = firstRing[i].second - firstRing[i-1].second
                        totalLength += kotlin.math.sqrt(dxL * dxL + dyL * dyL)
                    }
                    var accLength = 0f
                    var midPoint: Pair<Float, Float>? = null
                    var angle = 0f
                    for (i in 1 until firstRing.size) {
                        val dxL = firstRing[i].first - firstRing[i-1].first
                        val dyL = firstRing[i].second - firstRing[i-1].second
                        val segLen = kotlin.math.sqrt(dxL * dxL + dyL * dyL)
                        if (accLength + segLen >= totalLength / 2) {
                            val t = if (segLen == 0f) 0f else (totalLength / 2 - accLength) / segLen
                            val x = firstRing[i-1].first + t * dxL
                            val y = firstRing[i-1].second + t * dyL
                            midPoint = x to y
                            angle = kotlin.math.atan2(dyL, dxL) * 180f / kotlin.math.PI.toFloat()
                            break
                        }
                        accLength += segLen
                    }
                    if (midPoint != null) midPoint to angle else null
                } else null
            }
            else -> null
        }

        if (textField.isBlank() || textField.length > 256) return
        if (fontSize.isNaN() || fontSize <= 0f || fontSize > 512f) return
        if (textHaloWidth.isNaN() || textHaloWidth < 0f || textHaloWidth > 128f) return
        if (positionAndAngle == null || positionAndAngle.first.first.isNaN() || positionAndAngle.first.second.isNaN()) return

        val (position, rawAngle) = positionAndAngle
        var angle = rawAngle
        if (angle > 90f || angle < -90f) {
            angle += 180f
        }

        if (angle.isNaN() || angle.isInfinite() || position.first.isNaN() || position.second.isNaN()) {
            println("[SymbolLayerPainter] Skip text: invalid angle or position. angle=$angle, pos=$position, text='$textField'")
            return
        }

        val textStyle = TextStyle(
            color = textColor.copy(alpha = textOpacity),
            fontSize = fontSize.sp
        )

        val textLayoutResult = textMeasurer.measure(
            AnnotatedString(textField),
            style = textStyle,
            maxLines = 1
        )
        val textWidth = textLayoutResult.size.width.toFloat()
        val textHeight = textLayoutResult.size.height.toFloat()
        val baseline = textLayoutResult.firstBaseline.toFloat()
        val drawX = position.first + dx + anchorDx - textWidth / 2
        val drawY = position.second + dy + anchorDy - textHeight / 2

        val padding = 2f
        val placement = LabelPlacement(
            text = textField,
            position = position,
            angle = angle,
            bounds = Rect(
                left = drawX - padding,
                top = drawY - padding,
                right = drawX + textWidth + padding,
                bottom = drawY + textHeight + padding
            ),
            priority = 0,
            allowOverlap = false,
            ignorePlacement = false,
            textLayoutResult = textLayoutResult
        )
        val hasCollision = collisionDetector.hasCollision(placement)
        if (hasCollision) return
        try {
            // Visualize a border around text (for debugging)
            canvas.rotate(
                angle,
                Offset(position.first + dx + anchorDx, position.second + dy + anchorDy)
            ) {
                drawRect(
                    color = Color.Green.copy(alpha = 0.7f),
                    topLeft = Offset(drawX, drawY),
                    size = androidx.compose.ui.geometry.Size(textWidth, textHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
            }
            if (textHaloWidth > 0f) {
                val haloLayoutResult = textMeasurer.measure(
                    AnnotatedString(textField),
                    style = textStyle.copy(color = textHaloColor),
                    maxLines = 1
                )
                canvas.rotate(
                    angle,
                    Offset(position.first + dx + anchorDx, position.second + dy + anchorDy)
                ) {
                    drawText(
                        textLayoutResult = haloLayoutResult,
                        topLeft = Offset(drawX, drawY),
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = textHaloWidth * 2)
                    )
                }
            }
            canvas.rotate(
                angle,
                Offset(position.first + dx + anchorDx, position.second + dy + anchorDy)
            ) {
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(drawX, drawY)
                )
            }
            collisionDetector.addPlacement(placement)
        } catch (e: IllegalArgumentException) {
            println("Text draw error: ${e.message}")
        }
    }
}