package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import ovh.plrapps.mapcompose.maplibre.data.SDF
import ovh.plrapps.mapcompose.maplibre.data.SpriteManager
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.maplibre.spec.style.symbol.TextAnchor
import ovh.plrapps.mapcompose.maplibre.utils.obb.ObbPoint
import kotlin.math.atan2
import kotlin.math.sqrt
import ovh.plrapps.mapcompose.maplibre.renderer.collision.LineLabelPlacement
import kotlin.math.pow
import androidx.compose.ui.unit.Constraints
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.renderer.collision.LabelPlacement
import ovh.plrapps.mapcompose.maplibre.utils.obb.Size as ObbSize
import ovh.plrapps.mapcompose.maplibre.utils.obb.OBB
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult

sealed class Symbol(open val lp: LabelPlacement) {
    class Sprite(val value: ImageBitmap, override val lp: LabelPlacement) : Symbol(lp)
    class Text(val value: TextLayoutResult, override val lp: LabelPlacement) : Symbol(lp)
}

data class SymbolPlacement(
    val position: ObbPoint,
    val angle: Float,
    val size: Size = Size.Zero
)

data class DrawnElement(
    val size: IntSize,
    val type: ElementType,
    val info: String = ""
)

enum class ElementType {
    TEXT, SPRITE
}

private fun ObbPoint.toPoint() = Point(x = this.x.toDouble(), y = this.y.toDouble())

private fun findNearestPoint(point: Point, drawnElements: Map<Point, DrawnElement>, tolerance: Double = 5.0): Point? {
    return drawnElements.entries.minByOrNull { (existingPoint, element) ->
        val dx = existingPoint.x - point.x
        val dy = existingPoint.y - point.y
        // We take into account the dimensions of the element when calculating the distance
        val elementSize = element.size
        val adjustedDy = if (element.type == ElementType.TEXT) {
            // For text, we take into account its height
            dy - elementSize.height / 2
        } else {
            dy
        }
        dx * dx + adjustedDy * adjustedDy
    }?.takeIf { (existingPoint, element) ->
        val dx = existingPoint.x - point.x
        val dy = existingPoint.y - point.y
        // Increasing the text tolerance
        val adjustedTolerance = if (element.type == ElementType.TEXT) {
            tolerance + element.size.height / 2
        } else {
            tolerance
        }
        dx * dx + dy * dy <= adjustedTolerance * adjustedTolerance
    }?.key
}

class SymbolLayerPainter(
    private val textMeasurer: TextMeasurer,
    private val spriteManager: SpriteManager?,
    private val configuration: MapLibreConfiguration,
) {
    private val collisionDetectionEnabled: Boolean = configuration.collisionDetectionEnabled
    private val DEFAULT_ICON_SCALE = 1f
    private val DEBUG_COLOR = Color(0x80800080)
    private val geometryDecoders = GeometryDecoders()

    private fun substituteTemplate(template: String, properties: Map<String, Any?>?): String {
        val lang = configuration.lang?.code
        if (properties == null) return template

        // Without curly braces - just substitute the desired name
        if (!template.contains("{")) {
            if (lang != null) {
                val localized = properties["name:$lang"]?.toString()
                if (!localized.isNullOrBlank()) return localized
            }
            return properties["name"]?.toString() ?: template
        }

        // In the template - we substitute by key if it is name:lang, otherwise name, otherwise just by key
        return Regex("\\{([^}]+)\\}").replace(template) { matchResult ->
            val key = matchResult.groupValues[1]
            if (lang != null && key == "name:$lang") {
                val localized = properties["name:$lang"]?.toString()
                if (!localized.isNullOrBlank()) return@replace localized
                return@replace properties["name"]?.toString() ?: ""
            }
            if (key == "name") {
                return@replace properties["name"]?.toString() ?: ""
            }
            properties[key]?.toString() ?: ""
        }
    }

    private fun calculatePointPlacement(
        feature: Tile.Feature,
        extent: Int,
        canvasSize: Int
    ): SymbolPlacement? {
        val points = geometryDecoders.decodePoint(geometry = feature.geometry, extent = extent, canvasSize = canvasSize)
        return points.firstOrNull()?.let {
            SymbolPlacement(
                position = ObbPoint(it.x.toFloat(), it.y.toFloat()),
                angle = 0f
            )
        }
    }

    private fun drawDebugRect(canvas: DrawScope, labelPlacement: LabelPlacement) {
        canvas.rotate(labelPlacement.angle, Offset(labelPlacement.position.x, labelPlacement.position.y)) {
            drawRect(
                color = DEBUG_COLOR,
                topLeft = Offset(labelPlacement.bounds.left, labelPlacement.bounds.top),
                size = Size(labelPlacement.bounds.width, labelPlacement.bounds.height),
                style = Stroke(width = 2f)
            )
        }
    }

    private fun paintSymbol(collisionDetector: CollisionDetector, symbol: Symbol, canvas: DrawScope) {
        if (configuration.enableDebugSymbolsBoundingBox) {
            drawDebugRect(canvas = canvas, labelPlacement = symbol.lp)
        }

        if (collisionDetectionEnabled) {
            if (!collisionDetector.tryPlaceLabel(symbol.lp)) {
                return
            }
        }

        canvas.apply {
            rotate(symbol.lp.angle, Offset(symbol.lp.position.x, symbol.lp.position.y)) {
                when (symbol) {
                    is Symbol.Sprite -> {
                        drawImage(
                            image = symbol.value,
                            dstOffset = IntOffset(
                                (symbol.lp.position.x - symbol.lp.bounds.width / 2).toInt(),
                                (symbol.lp.position.y - symbol.lp.bounds.height / 2).toInt()
                            ),
                            dstSize = IntSize(
                                symbol.lp.bounds.width.toInt(),
                                symbol.lp.bounds.height.toInt()
                            )
                        )
                    }

                    is Symbol.Text -> {
                        drawText(
                            textLayoutResult = symbol.value,
                            topLeft = Offset(
                                symbol.lp.position.x - symbol.lp.bounds.width / 2,
                                symbol.lp.position.y - symbol.lp.bounds.height / 2
                            )
                        )
                    }
                }
            }
        }
    }

    private val regexForSubProcess = "\\{([^}]+)\\}".toRegex()

    private fun subProcess(
        input: String,
        featureProperties: Map<String, Any?>?
    ): String {
        return if (input.firstOrNull() == '{') {
            val matchResult = regexForSubProcess.find(input)
            if (matchResult != null) {
                val key = matchResult.groupValues[1]
                val propValue = featureProperties?.get(key)?.toString() ?: ""
                input.replace("{$key}", propValue)
            } else {
                input
            }
        } else {
            input
        }
    }

    private fun paintSprite(
        canvas: DrawScope,
        drawnElements: MutableMap<Point, DrawnElement>,
        collisionDetector: CollisionDetector,
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        offsetInViewport: Offset,
        zoom: Double,
    ) {
        val spriteManager = spriteManager ?: return
        val paint = style.paint ?: return
        val layout = style.layout ?: return

        val spriteId = layout.iconImage?.process(featureProperties, actualZoom)?.let { subProcess(it, featureProperties) } ?: return
        val spriteInfo = spriteManager.getSpriteInfo(spriteId)
        if (spriteInfo == null) {
            return
        }

        val iconSizeExpr = layout.iconSize
        val iconScale: Float = iconSizeExpr?.process(featureProperties, actualZoom)?.toFloat() ?: DEFAULT_ICON_SCALE
        val iconColorExpr = paint.iconColor
        val iconColor = iconColorExpr?.process(featureProperties, actualZoom)
        val iconHaloColorExpr = paint.iconHaloColor
        val iconHaloColor = iconHaloColorExpr?.process(featureProperties, actualZoom)
        val iconOpacityExpr = paint.iconOpacity
        val iconOpacity = iconOpacityExpr?.process(featureProperties, actualZoom)?.toFloat() ?: 1f
        if (iconOpacity == 0f) {
            return
        }
        val scale = iconScale * canvas.density

        val sdf = if (spriteInfo.sdf) {
            iconColor?.let { fillColor ->
                SDF(fillColor = fillColor)
            }?.let {
                if (iconHaloColor != null) {
                    it.copy(haloColor = iconHaloColor)
                } else it
            }
        } else null

        val spritePair = spriteManager.getSprite(spriteId, iconColor, sdf)
        if (spritePair == null) {
            return
        }
        val (spriteMeta, sprite) = spritePair

        val size = IntSize(
            (spriteMeta.width * scale).toInt(),
            (spriteMeta.height * scale).toInt()
        )

        val spritePosition = ObbPoint(placement.position.x, placement.position.y)

        // Check if there is already drawn text at this point or nearby
        val existingText = findNearestPoint(placement.position.toPoint(), drawnElements)?.let { point ->
            drawnElements[point]?.takeIf { it.type == ElementType.TEXT }
        }

        // Calculate the Y offset depending on the presence of text
        val spriteOffsetY = if (existingText != null) {
            -existingText.size.height / 2 // up the sprite up to half the height of the text
        } else {
            0
        }

        LabelPlacement(
            text = "sprite_$spriteId",
            position = ObbPoint(
                spritePosition.x + offsetInViewport.x,
                spritePosition.y + offsetInViewport.y + spriteOffsetY
            ),
            angle = placement.angle,
            bounds = Rect(
                left = spritePosition.x + offsetInViewport.x - size.width / 2f,
                top = spritePosition.y + offsetInViewport.y - size.height / 2f + spriteOffsetY,
                right = spritePosition.x + offsetInViewport.x + size.width / 2f,
                bottom = spritePosition.y + offsetInViewport.y + size.height / 2f + spriteOffsetY
            ),
            obb = OBB(
                ObbPoint(spritePosition.x + offsetInViewport.x, spritePosition.y + offsetInViewport.y + spriteOffsetY),
                ObbSize(size.width.toFloat(), size.height.toFloat()),
                placement.angle
            ),
            priority = layout.symbolZOrder?.process(featureProperties, actualZoom)?.toInt() ?: 0,
            allowOverlap = layout.iconAllowOverlap?.process(featureProperties, actualZoom) ?: false,
            ignorePlacement = layout.iconIgnorePlacement?.process(featureProperties, actualZoom) ?: false
        ).let { paintSymbol(collisionDetector, Symbol.Sprite(sprite, it), canvas) }

        // Save the point with clean coordinates from placement.position and sprite information
        val point = placement.position.toPoint()
        drawnElements[point] = DrawnElement(
            size = size,
            type = ElementType.SPRITE,
            info = "Sprite: $spriteId at $point"
        )
    }

    /**
     * Sets the angle of the caption to the range where the text always reads from left to right (or bottom to top for vertical lines).
     * If the angle is outside [-90, 90] degrees, it is flipped 180°.
     * This prevents the text from appearing upside down on the lines.
     * @param angle the original angle (in degrees)
     * @return the angle to display the text correctly
     */
    private fun makeTextUpright(angle: Float): Float {
        return if (angle > 90f || angle < -90f) angle + 180f else angle
    }

    private fun paintText(
        canvas: DrawScope,
        drawnElements: MutableMap<Point, DrawnElement>,
        collisionDetector: CollisionDetector,
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        offsetInViewport: Offset,
        lineStrings: List<List<Pair<Float, Float>>>? = null
    ) {
        val layout = style.layout ?: return
        val paint = style.paint ?: return
        val spriteSize = findNearestPoint(placement.position.toPoint(), drawnElements)?.let { point ->
            drawnElements[point]?.takeIf { it.type == ElementType.SPRITE }?.size
        }
        val templateTextField = layout.textField?.process(featureProperties, actualZoom) ?: return
        val textField = substituteTemplate(templateTextField, featureProperties)
        if (textField.isBlank() || textField.length > 256) {
            return
        }

        val textSizeExpr = layout.textSize
        val fontSize = (textSizeExpr?.process(featureProperties, actualZoom)?.toFloat() ?: 16f)
        val textMaxWidth =
            layout.textMaxWidth?.process(featureProperties, actualZoom)?.toFloat() ?: Float.POSITIVE_INFINITY

        val textColorExpr = paint.textColor
        val textColor = textColorExpr?.process(featureProperties, actualZoom) ?: Color.Black
        val textOpacityExpr = paint.textOpacity
        val textOpacity = textOpacityExpr?.process(featureProperties, actualZoom)?.toFloat() ?: 1f
        if (textOpacity == 0f) {
            return
        }
        val textHaloColorExpr = paint.textHaloColor
        val textHaloColor = textHaloColorExpr?.process(featureProperties, actualZoom) ?: Color.White
        val textHaloWidthExpr = paint.textHaloWidth
        val textHaloWidth =
            (textHaloWidthExpr?.process(featureProperties, actualZoom)?.toFloat()?.let { it * canvas.density } ?: 0f)
        val textOffsetExpr = layout.textOffset
        val offsetArr = textOffsetExpr?.process(featureProperties, actualZoom) ?: listOf(0.0, 0.0)
        val textAnchorExpr = layout.textAnchor
        val anchor = textAnchorExpr?.process(featureProperties, actualZoom) ?: TextAnchor.Center

        val dx = (offsetArr.getOrNull(0) ?: 0.0).toFloat() * fontSize
        val dy = (offsetArr.getOrNull(1) ?: 0.0).toFloat() * fontSize

        val textStyle = TextStyle(
            color = textColor.copy(alpha = textOpacity),
            fontSize = fontSize.sp,
            textAlign = if (spriteSize != null) TextAlign.Center else TextAlign.Unspecified,
            shadow = if (textHaloWidth > 0f) {
                Shadow(
                    color = textHaloColor,
                    offset = Offset.Zero,
                    blurRadius = textHaloWidth * 2
                )
            } else null
        )

        val maxLines = if (spriteSize == null) 1 else 5
        val useMaxWidth = spriteSize != null && textMaxWidth.isFinite()
        val constraints = if (useMaxWidth) {
            Constraints(maxWidth = (textMaxWidth * (fontSize + 2f) * canvas.density).toInt())
        } else {
            Constraints()
        }

        val textLayoutResult = textMeasurer.measure(
            AnnotatedString(textField),
            style = textStyle,
            maxLines = maxLines,
            constraints = constraints,
            softWrap = true
        )

        val textWidth = textLayoutResult.size.width.toFloat()
        val textHeight = textLayoutResult.size.height.toFloat()
        val verticalGap = 1f

        if (lineStrings != null && lineStrings.isNotEmpty()) {
            val symbolSpacing = layout.symbolSpacing?.process(featureProperties, actualZoom)?.toFloat() ?: 250f
            var anyPlaced = false
            var maxLength = 0f
            var maxLine: List<Pair<Float, Float>>? = null

            for (line in lineStrings) {
                if (line.size < 2) continue
                val lineLength = line.zipWithNext { a, b ->
                    val dx = a.first - b.first
                    val dy = a.second - b.second
                    sqrt(dx * dx + dy * dy)
                }.sum()

                if (lineLength > maxLength) {
                    maxLength = lineLength
                    maxLine = line
                }

                // We place it only if the text fits completely on the line.
                if (lineLength < textWidth) continue
                val placements = LineLabelPlacement.calculatePlacements(line, textWidth, symbolSpacing)
                if (placements.isNotEmpty()) anyPlaced = true
                for ((pos, angle) in placements) {
                    // Check that the text does not go beyond the line
                    val distToStart =
                        sqrt((pos.first - line.first().first).pow(2) + (pos.second - line.first().second).pow(2))
                    val distToEnd =
                        sqrt((pos.first - line.last().first).pow(2) + (pos.second - line.last().second).pow(2))
                    if (distToStart < textWidth / 2 || distToEnd < textWidth / 2) continue
                    val x = pos.first + dx
                    val y = pos.second + dy
                    val displayAngle = makeTextUpright(angle)

                    try {
                        LabelPlacement(
                            text = textField,
                            position = ObbPoint(x + offsetInViewport.x, y + offsetInViewport.y),
                            angle = displayAngle,
                            bounds = Rect(
                                left = x + offsetInViewport.x - textWidth / 2f,
                                top = y + offsetInViewport.y - textHeight / 2f,
                                right = x + offsetInViewport.x + textWidth / 2f,
                                bottom = y + offsetInViewport.y + textHeight / 2f
                            ),
                            obb = OBB(
                                ObbPoint(x + offsetInViewport.x, y + offsetInViewport.y),
                                ObbSize(textWidth, textHeight),
                                displayAngle
                            ),
                            priority = layout.symbolZOrder?.process(featureProperties, actualZoom)?.toInt() ?: 0,
                            allowOverlap = layout.textAllowOverlap?.process(featureProperties, actualZoom) ?: false,
                            ignorePlacement = layout.textIgnorePlacement?.process(featureProperties, actualZoom)
                                ?: false
                        ).let { paintSymbol(collisionDetector, Symbol.Text(textLayoutResult, it), canvas) }

                    } catch (_: IllegalArgumentException) {

                    }
                }
            }

            // If there is no signature on any segment, we place it in the center of the longest one (if it fits)
            if (!anyPlaced && maxLine != null && maxLine.size >= 2) {
                val maxLineLength = maxLine.zipWithNext { a, b ->
                    val dx = a.first - b.first
                    val dy = a.second - b.second
                    sqrt(dx * dx + dy * dy)
                }.sum()

                if (maxLineLength >= textWidth) {
                    val midIdx = maxLine.size / 2
                    val p1 = maxLine[midIdx - 1]
                    val p2 = maxLine[midIdx]
                    val x = (p1.first + p2.first) / 2 + dx
                    val y = (p1.second + p2.second) / 2 + dy
                    val angle = atan2(p2.second - p1.second, p2.first - p1.first) * 180f / kotlin.math.PI.toFloat()
                    val displayAngle = makeTextUpright(angle)
                    // Check that the text does not go beyond the line
                    val distToStart = sqrt((x - maxLine.first().first).pow(2) + (y - maxLine.first().second).pow(2))
                    val distToEnd = sqrt((x - maxLine.last().first).pow(2) + (y - maxLine.last().second).pow(2))
                    if (distToStart >= textWidth / 2 && distToEnd >= textWidth / 2) {

                        try {
                            // Create a LabelPlacement with the same parameters as for drawing
                            LabelPlacement(
                                text = textField,
                                position = ObbPoint(x + offsetInViewport.x, y + offsetInViewport.y),
                                angle = displayAngle,
                                bounds = Rect(
                                    left = x + offsetInViewport.x - textWidth / 2f,
                                    top = y + offsetInViewport.y - textHeight / 2f,
                                    right = x + offsetInViewport.x + textWidth / 2f,
                                    bottom = y + offsetInViewport.y + textHeight / 2f
                                ),
                                obb = OBB(
                                    ObbPoint(x + offsetInViewport.x, y + offsetInViewport.y),
                                    ObbSize(textWidth, textHeight),
                                    displayAngle
                                ),
                                priority = layout.symbolZOrder?.process(featureProperties, actualZoom)?.toInt() ?: 0,
                                allowOverlap = layout.textAllowOverlap?.process(featureProperties, actualZoom) ?: false,
                                ignorePlacement = layout.textIgnorePlacement?.process(featureProperties, actualZoom)
                                    ?: false
                            ).let { paintSymbol(collisionDetector, Symbol.Text(textLayoutResult, it), canvas) }

                        } catch (_: IllegalArgumentException) {

                        }
                    }
                }
            }
            return
        }

        val textPosition = if (spriteSize != null) {
            val x = placement.position.x + dx
            // For text with sprite we always use vertical centering
            val y = placement.position.y + spriteSize.height / 2f + verticalGap + textHeight / 2f
            ObbPoint(x, y)
        } else {
            val x = placement.position.x + dx
            val y = placement.position.y + dy
            when (anchor) {
                TextAnchor.Top -> ObbPoint(x, y - textHeight / 2)
                TextAnchor.Bottom -> ObbPoint(x, y + textHeight / 2)
                TextAnchor.Left -> ObbPoint(x - textWidth / 2, y)
                TextAnchor.Right -> ObbPoint(x + textWidth / 2, y)
                TextAnchor.TopLeft -> ObbPoint(x - textWidth / 2, y - textHeight / 2)
                TextAnchor.TopRight -> ObbPoint(x + textWidth / 2, y - textHeight / 2)
                TextAnchor.BottomLeft -> ObbPoint(x - textWidth / 2, y + textHeight / 2)
                TextAnchor.BottomRight -> ObbPoint(x + textWidth / 2, y + textHeight / 2)
                TextAnchor.Center -> ObbPoint(x, y)
            }
        }

        try {
            LabelPlacement(
                text = textField,
                position = ObbPoint(textPosition.x + offsetInViewport.x, textPosition.y + offsetInViewport.y),
                angle = placement.angle,
                bounds = Rect(
                    left = textPosition.x + offsetInViewport.x - textWidth / 2f,
                    top = textPosition.y + offsetInViewport.y - textHeight / 2f,
                    right = textPosition.x + offsetInViewport.x + textWidth / 2f,
                    bottom = textPosition.y + offsetInViewport.y + textHeight / 2f
                ),
                obb = OBB(
                    ObbPoint(textPosition.x + offsetInViewport.x, textPosition.y + offsetInViewport.y),
                    ObbSize(textWidth, textHeight),
                    placement.angle
                ),
                priority = layout.symbolZOrder?.process(featureProperties, actualZoom)?.toInt() ?: 0,
                allowOverlap = layout.textAllowOverlap?.process(featureProperties, actualZoom) ?: false,
                ignorePlacement = layout.textIgnorePlacement?.process(featureProperties, actualZoom) ?: false
            ).let { paintSymbol(collisionDetector, Symbol.Text(textLayoutResult, it), canvas) }

            // Save the point with clean coordinates from placement.position and text
            val point = placement.position.toPoint()
            drawnElements[point] = DrawnElement(
                size = textLayoutResult.size,
                type = ElementType.TEXT,
                info = "Text: '$textField' at $point"
            )
        } catch (_: IllegalArgumentException) {

        }
    }

    fun paint(
        canvas: DrawScope,
        drawnElements: MutableMap<Point, DrawnElement>,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: SymbolLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        offsetInViewport: Offset,
    ) {
        val layout = style.layout ?: return
        val paint = style.paint ?: return

        // Calculate the symbol placement
        val placement: SymbolPlacement?
        var lineStrings: List<List<Pair<Float, Float>>>? = null
        if (feature.type == Tile.GeomType.POINT) {
            placement = calculatePointPlacement(feature = feature, extent = extent, canvasSize = canvasSize)
        } else if (layout.symbolPlacement?.process(featureProperties, actualZoom) == "line" &&
            feature.type == Tile.GeomType.LINESTRING
        ) {
            lineStrings = geometryDecoders.decodeLine(
                geometry = feature.geometry,
                extent = extent,
                canvasSize = canvasSize
            )
            placement = lineStrings.firstOrNull()?.firstOrNull()?.let {
                SymbolPlacement(
                    position = ObbPoint(it.first, it.second),
                    angle = 0f
                )
            }
        } else {
            placement = null
        }
        if (placement == null) return


        if (layout.iconImage != null && spriteManager != null) {
            paintSprite(
                canvas = canvas,
                drawnElements = drawnElements,
                collisionDetector = collisionDetector,
                placement = placement,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                offsetInViewport = offsetInViewport,
                zoom = zoom
            )
        }


        if (layout.textField != null) {
            paintText(
                canvas = canvas,
                drawnElements = drawnElements,
                collisionDetector = collisionDetector,
                placement = placement,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                offsetInViewport = offsetInViewport,
                lineStrings = lineStrings
            )
        }
    }
}
