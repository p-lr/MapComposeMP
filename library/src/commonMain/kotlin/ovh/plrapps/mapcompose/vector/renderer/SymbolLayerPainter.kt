package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SDF
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.collision.LabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.collision.LineLabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.collision.OverlapMode
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsBoolean
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsStringList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.graphics.toArgb
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import kotlin.collections.zipWithNext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class CompoundLabelPlacement(
    val spritePlacement: LabelPlacement,
    val textPlacement: LabelPlacement?
)

data class TextPlacementCandidate(
    val labelPlacement: LabelPlacement,
    val mercatorX: Double,
    val mercatorY: Double,
    val dx: Float,
    val dy: Float,
)

sealed class Symbol(
    val id: String,
    val global: Point,
    val placement: CompoundLabelPlacement,
    val align: Offset = IN_CENTER,
    val viewportAligned: Boolean = true,
) {
    class Sprite(
        id: String,
        global: Point,
        placement: CompoundLabelPlacement,
        val value: ImageBitmap,
        viewportAligned: Boolean = true,
    ) : Symbol(id, global, placement, viewportAligned = viewportAligned)

    class Text(
        id: String,
        global: Point,
        placement: CompoundLabelPlacement,
        val value: TextLayoutResult,
        viewportAligned: Boolean = true,
        val spriteAnchorGlobal: Point? = null,
        val textOffset: Pair<Float, Float>? = null,
    ) : Symbol(id, global, placement, viewportAligned = viewportAligned)

    class SpriteWithText(
        id: String,
        global: Point,
        placement: CompoundLabelPlacement,
        align: Offset,
        val sprite: ImageBitmap,
        val text: TextLayoutResult,
        val spriteSize: IntSize,
        val textSize: IntSize,
        val verticalGap: Float,
        val iconOptional: Boolean,
        val textOptional: Boolean,
        viewportAligned: Boolean = true,
        val textCandidates: List<TextPlacementCandidate> = emptyList(),
    ) : Symbol(id, global, placement, align, viewportAligned)

    fun draw(drawScope: DrawScope) {
        val s = getInPixels()
        val symbolCenter = Offset(s.width / 2f, s.height / 2f)
        with(drawScope) {
            when (this@Symbol) {
                is Sprite -> {
                    rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                        drawImage(
                            image = value,
                            dstSize = IntSize(
                                placement.spritePlacement.bounds.width.toInt(),
                                placement.spritePlacement.bounds.height.toInt()
                            )
                        )
                    }
                }

                is Text -> {
                    // For text, use the corner from textPlacement (if available) or spritePlacement
                    val textAngle =
                        placement.textPlacement?.angle ?: placement.spritePlacement.angle
                    rotate(textAngle, pivot = symbolCenter) {
                        val centerX = (s.width - value.size.width) / 2f
                        val centerY = (s.height - value.size.height) / 2f
                        val topLeft = Offset(centerX, centerY)

                        drawText(
                            textLayoutResult = value,
                            topLeft = topLeft
                        )
                    }
                }

                is SpriteWithText -> {
                    rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                        // For SpriteWithText offset is already taken into account in the position, you need to correctly place the elements inside the Canvas
                        // The sprite should be horizontally centered
                        val spriteLeft = (s.width - spriteSize.width) / 2f
                        val spriteTop = 0f

                        // The text should be centered relative to the sprite
                        val textLeft = (s.width - textSize.width) / 2f
                        val textTop = spriteSize.height + verticalGap

                        drawImage(
                            image = sprite,
                            dstSize = spriteSize,
                            dstOffset = IntOffset(spriteLeft.toInt(), spriteTop.toInt())
                        )

                        // Draw text under the sprite
                        drawText(
                            textLayoutResult = text,
                            topLeft = Offset(textLeft, textTop)
                        )
                    }
                }
            }
        }
    }

    fun getInPixels(): Size {
        return when (this) {
            is SpriteWithText -> {
                val totalWidth = max(spriteSize.width, textSize.width)
                val totalHeight = (spriteSize.height + verticalGap + textSize.height)
                Size(totalWidth.toFloat(), totalHeight)
            }

            is Sprite -> {
                Size(
                    placement.spritePlacement.bounds.width,
                    placement.spritePlacement.bounds.height
                )
            }

            is Text -> {
                Size(
                    placement.textPlacement!!.bounds.width,
                    placement.textPlacement.bounds.height
                )
            }
        }
    }

    companion object {
        val IN_CENTER = Offset(-0.5f, -0.5f)
    }
}

data class SymbolPlacement(
    val position: ObbPoint,
    val angle: Float,
    val size: Size = Size.Zero
)

class SymbolLayerPainter(
    private val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    private val spriteManager: SpriteManager?,
    private val configuration: MapLibreConfiguration,
    private val pathCache: LruCache<String, Any>,
    private val mutex: Mutex
) {
    private val DEFAULT_ICON_SCALE = 1f

    private val geometryDecoders = GeometryDecoders()

    private fun resolveViewportAligned(alignmentValue: String?, defaultViewportAligned: Boolean): Boolean =
        when (alignmentValue) {
            "map"      -> false
            "viewport" -> true
            else       -> defaultViewportAligned
        }

    private fun resolveIconOverlapMode(
        layout: SymbolLayout,
        props: Map<String, Any?>?,
        zoom: Double
    ): OverlapMode =
        layout.iconOverlap?.processAsString(props, zoom)?.let { v ->
            when (v) {
                "always" -> OverlapMode.Always
                "cooperative" -> OverlapMode.Cooperative
                else -> OverlapMode.Never
            }
        } ?: if (layout.iconAllowOverlap?.processAsBoolean(props, zoom) == true) OverlapMode.Always else OverlapMode.Never

    private fun resolveTextOverlapMode(
        layout: SymbolLayout,
        props: Map<String, Any?>?,
        zoom: Double
    ): OverlapMode =
        layout.textOverlap?.processAsString(props, zoom)?.let { v ->
            when (v) {
                "always" -> OverlapMode.Always
                "cooperative" -> OverlapMode.Cooperative
                else -> OverlapMode.Never
            }
        } ?: if (layout.textAllowOverlap?.processAsBoolean(props, zoom) == true) OverlapMode.Always else OverlapMode.Never

    /**
     * Transformation to normalized Mercator coordinates
     */
    private fun tileCoordToNormalized(
        tileX: Int,
        tileY: Int,
        pixelX: Double,
        pixelY: Double,
        zoom: Double,
        tileSize: Int
    ): Point {
        val n = 2.0.pow(zoom)

        val normalizedX = (tileX * tileSize + pixelX) / (tileSize * n)
        val normalizedY = (tileY * tileSize + pixelY) / (tileSize * n)

        return Point(normalizedX, normalizedY)
    }

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
        return regexForSubProcess.replace(template) { matchResult ->
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

    private fun produceSprite(
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        zoom: Double,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int
    ): Symbol? {
        val spriteManager = spriteManager ?: return null
        val paint = style.paint ?: return null
        val layout = style.layout ?: return null

        val spriteId =
            layout.iconImage?.processAsString(featureProperties, actualZoom)?.let { subProcess(it, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId)
            ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: DEFAULT_ICON_SCALE
        val iconColor = paint.iconColor?.processAsColor(featureProperties, actualZoom)
        val iconHaloColor = paint.iconHaloColor?.processAsColor(featureProperties, actualZoom)
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: 1f
        if (iconOpacity == 0f) {
            return null
        }
        val scale = iconScale * density.density

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
            return null
        }
        val (spriteMeta, sprite) = spritePair

        val size = IntSize(
            (spriteMeta.width.toFloat() * scale).toInt(),
            (spriteMeta.height.toFloat() * scale).toInt()
        )

        val spritePosition = ObbPoint(placement.position.x, placement.position.y)

        // Direct conversion of tile coordinates to normalized MapCompose coordinates
        val normalizedPoint = tileCoordToNormalized(
            tileX = tileX,
            tileY = tileY,
            pixelX = spritePosition.x.toDouble(),
            pixelY = spritePosition.y.toDouble(),
            zoom = zoom,
            tileSize = canvasSize
        )
        val globalX = normalizedPoint.x
        val globalY = normalizedPoint.y

        val iconPadding = (layout.iconPadding.processAsFloat(featureProperties, actualZoom) ?: 2f) * density.density

        // Use double for exact calculations, then convert to float
        val leftBound = (spritePosition.x.toDouble() - size.width.toDouble() / 2.0).toFloat() - iconPadding
        val topBound = (spritePosition.y.toDouble() - size.height.toDouble() / 2.0).toFloat() - iconPadding
        val rightBound = (spritePosition.x.toDouble() + size.width.toDouble() / 2.0).toFloat() + iconPadding
        val bottomBound = (spritePosition.y.toDouble() + size.height.toDouble() / 2.0).toFloat() + iconPadding

        val bounds = Rect(
            left = leftBound,
            top = topBound,
            right = rightBound,
            bottom = bottomBound
        )

        val iconRotateDeg = layout.iconRotate.processAsFloat(featureProperties, actualZoom) ?: 0f
        val spriteAngle = placement.angle + iconRotateDeg

        return LabelPlacement(
            text = "sprite_$spriteId",
            position = ObbPoint(
                spritePosition.x,
                spritePosition.y
            ),
            angle = spriteAngle,
            bounds = bounds,
            obb = OBB(
                ObbPoint(spritePosition.x, spritePosition.y),
                ovh.plrapps.mapcompose.vector.utils.obb.Size(size.width + 2 * iconPadding, size.height + 2 * iconPadding),
                spriteAngle
            ),
            layerIndex = layerIndex,
            inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
            overlapMode = resolveIconOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.iconIgnorePlacement?.processAsBoolean(featureProperties, actualZoom) ?: false
        ).let {
            val isLinePlacement = layout.symbolPlacement?.processAsString(featureProperties, actualZoom)
                ?.let { p -> p == "line" || p == "line-center" } ?: false
            val iconViewportAligned = resolveViewportAligned(
                layout.iconRotationAlignment?.processAsString(featureProperties, actualZoom),
                defaultViewportAligned = !isLinePlacement
            )
            Symbol.Sprite(
                id = id,
                global = Point(globalX, globalY),
                placement = CompoundLabelPlacement(it, null),
                value = sprite,
                viewportAligned = iconViewportAligned,
            )
        }
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

    private fun produceSpriteWithText(
        id: String,
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        zoom: Double,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int
    ): Symbol? {
        val textMeasurer = textMeasurerState.value ?: return null

        val layout = style.layout ?: return null
        val paint = style.paint ?: return null
        val spriteManager = spriteManager ?: return null

        val spriteId =
            layout.iconImage?.processAsString(featureProperties, actualZoom)?.let { subProcess(it, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId) ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: DEFAULT_ICON_SCALE
        val iconColor = paint.iconColor?.processAsColor(featureProperties, actualZoom)
        val iconHaloColor = paint.iconHaloColor?.processAsColor(featureProperties, actualZoom)
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: 1f
        if (iconOpacity == 0f) return null

        val scale = iconScale * density.density

        val sdf = if (spriteInfo.sdf) {
            iconColor?.let { fillColor ->
                SDF(fillColor = fillColor)
            }?.let {
                if (iconHaloColor != null) {
                    it.copy(haloColor = iconHaloColor)
                } else it
            }
        } else null

        val spritePair = spriteManager.getSprite(spriteId, iconColor, sdf) ?: return null
        val (spriteMeta, sprite) = spritePair

        val spriteSize = IntSize(
            (spriteMeta.width.toFloat() * scale).toInt(),
            (spriteMeta.height.toFloat() * scale).toInt()
        )

        // We receive the text
        val templateTextField = layout.textField?.processAsString(featureProperties, actualZoom) ?: return null
        val textField = substituteTemplate(templateTextField, featureProperties)
        if (textField.isBlank() || textField.length > 256) return null

        val fontSize = (layout.textSize.processAsFloat(featureProperties, actualZoom) ?: 16f)
        val textMaxWidth =
            layout.textMaxWidth.processAsFloat(featureProperties, actualZoom) ?: Float.POSITIVE_INFINITY

        val textColor = paint.textColor?.processAsColor(featureProperties, actualZoom) ?: Color.Black
        val textOpacity = paint.textOpacity.processAsFloat(featureProperties, actualZoom) ?: 1f
        if (textOpacity == 0f) return null

        val textHaloColor = paint.textHaloColor?.processAsColor(featureProperties, actualZoom) ?: Color.White
        val textHaloWidth = (paint.textHaloWidth.processAsFloat(featureProperties, actualZoom) ?: 0f)

        val iconOptional = layout.iconOptional?.processAsBoolean(featureProperties, actualZoom) ?: false
        val textOptional = layout.textOptional?.processAsBoolean(featureProperties, actualZoom) ?: false

        val textStyle = TextStyle(
            color = textColor.copy(alpha = textOpacity),
            fontSize = fontSize.sp,
            textAlign = TextAlign.Center,
            shadow = if (textHaloWidth > 0f) {
                Shadow(
                    color = textHaloColor,
                    offset = Offset.Zero,
                    blurRadius = textHaloWidth * 2
                )
            } else null
        )

        val textLayoutResult = textMeasurer.measure(
            text = AnnotatedString(textField),
            density = density,
            style = textStyle,
            maxLines = 5,
            constraints = if (textMaxWidth.isFinite()) {
                Constraints(maxWidth = (textMaxWidth * fontSize * density.density).toInt())
            } else {
                Constraints()
            },
            softWrap = true
        )

        val textSize = textLayoutResult.size
        val verticalGap = 2.0f * density.density

        // We calculate the overall dimensions
        val totalWidth = max(spriteSize.width, textSize.width)
        val totalHeight = spriteSize.height + verticalGap + textSize.height

        // The sprite position remains in the center of placement
        val spritePosition = placement.position

        // Normalized coordinates for the sprite's center
        val normalizedPoint = tileCoordToNormalized(
            tileX = tileX,
            tileY = tileY,
            pixelX = spritePosition.x.toDouble(),
            pixelY = spritePosition.y.toDouble(),
            zoom = zoom,
            tileSize = canvasSize
        )

        val iconPadding = (layout.iconPadding.processAsFloat(featureProperties, actualZoom) ?: 2f) * density.density
        val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: 2f) * density.density

        val iconRotateDeg = layout.iconRotate.processAsFloat(featureProperties, actualZoom) ?: 0f
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: 0f

        val spriteLabelPlacement = LabelPlacement(
            text = "sprite_$spriteId",
            position = spritePosition,
            angle = iconRotateDeg,
            bounds = Rect(
                left = spritePosition.x - spriteSize.width / 2f - iconPadding,
                top = spritePosition.y - spriteSize.height / 2f - iconPadding,
                right = spritePosition.x + spriteSize.width / 2f + iconPadding,
                bottom = spritePosition.y + spriteSize.height / 2f + iconPadding
            ),
            obb = OBB(
                spritePosition,
                ovh.plrapps.mapcompose.vector.utils.obb.Size(spriteSize.width + 2 * iconPadding, spriteSize.height + 2 * iconPadding),
                iconRotateDeg
            ),
            layerIndex = layerIndex,
            inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
            overlapMode = resolveIconOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.iconIgnorePlacement?.processAsBoolean(featureProperties, actualZoom) ?: false
        )

        // Position of text under sprite
        val textPosition = ObbPoint(
            spritePosition.x,
            spritePosition.y + spriteSize.height / 2f + verticalGap + textSize.height / 2f
        )

        // Create a LabelPlacement for the text
        val textLabelPlacement = LabelPlacement(
            text = textField,
            position = textPosition,
            angle = textRotateDeg,
            bounds = Rect(
                left = textPosition.x - textSize.width / 2f - textPadding,
                top = textPosition.y - textSize.height / 2f - textPadding,
                right = textPosition.x + textSize.width / 2f + textPadding,
                bottom = textPosition.y + textSize.height / 2f + textPadding
            ),
            obb = OBB(
                textPosition,
                ovh.plrapps.mapcompose.vector.utils.obb.Size(textSize.width + 2 * textPadding, textSize.height + 2 * textPadding),
                textRotateDeg
            ),
            layerIndex = layerIndex,
            inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
            overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom) ?: false
        )

        val variableAnchors = layout.textVariableAnchor?.processAsStringList(featureProperties, actualZoom)
        val textAnchorStr = TextAnchor.fromAny(layout.textAnchor?.processAsString(featureProperties, actualZoom))?.value ?: "bottom"
        val anchors: List<String> =
            if (!variableAnchors.isNullOrEmpty()) variableAnchors else listOf(textAnchorStr)

        val radialPx = (layout.textRadialOffset.processAsFloat(featureProperties, actualZoom) ?: 0f) *
                fontSize * density.density
        val hOff = spriteSize.width  / 2f + verticalGap + radialPx + textSize.width  / 2f
        val vOff = spriteSize.height / 2f + verticalGap + radialPx + textSize.height / 2f


        val textCandidates: List<TextPlacementCandidate> = anchors.map { anchor ->
            val (dx, dy) = when (anchor) {
                "top"          -> Pair(0f,    -vOff)
                "bottom"       -> Pair(0f,    +vOff)
                "left"         -> Pair(-hOff, 0f)
                "right"        -> Pair(+hOff, 0f)
                "top-left"     -> Pair(-hOff, -vOff)
                "top-right"    -> Pair(+hOff, -vOff)
                "bottom-left"  -> Pair(-hOff, +vOff)
                "bottom-right" -> Pair(+hOff, +vOff)
                "center"       -> Pair(0f,    0f)
                else           -> Pair(0f,    +vOff)
            }
            val cx = spritePosition.x + dx
            val cy = spritePosition.y + dy
            val norm = tileCoordToNormalized(tileX, tileY, cx.toDouble(), cy.toDouble(), zoom, canvasSize)
            TextPlacementCandidate(
                labelPlacement = LabelPlacement(
                    text = textField,
                    position = ObbPoint(cx, cy),
                    angle = textRotateDeg,
                    bounds = Rect(
                        cx - textSize.width / 2f  - textPadding,
                        cy - textSize.height / 2f - textPadding,
                        cx + textSize.width / 2f  + textPadding,
                        cy + textSize.height / 2f + textPadding
                    ),
                    obb = OBB(
                        ObbPoint(cx, cy),
                        ovh.plrapps.mapcompose.vector.utils.obb.Size(
                            textSize.width + 2 * textPadding,
                            textSize.height + 2 * textPadding
                        ),
                        textRotateDeg
                    ),
                    layerIndex = layerIndex,
                    inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
                    overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
                    ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom) ?: false
                ),
                mercatorX = norm.x,
                mercatorY = norm.y,
                dx = dx,
                dy = dy,
            )
        }

        val offsetY = -((spriteSize.height.toFloat() / 2f) / totalHeight)

        val centerOffset = Offset(
            x = -0.5f,
            y = offsetY
        )

        val viewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = true  // produceSpriteWithText is only called for point geometry
        )

        return Symbol.SpriteWithText(
            id = id,
            global = Point(normalizedPoint.x, normalizedPoint.y),
            placement = CompoundLabelPlacement(
                spritePlacement = spriteLabelPlacement,
                textPlacement = textLabelPlacement
            ),
            align = centerOffset,
            sprite = sprite,
            text = textLayoutResult,
            spriteSize = spriteSize,
            textSize = IntSize(textSize.width, textSize.height),
            verticalGap = verticalGap,
            iconOptional = iconOptional,
            textOptional = textOptional,
            viewportAligned = viewportAligned,
            textCandidates = textCandidates,
        )
    }

    private suspend fun produceText(
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        zoom: Double,
        lineStrings: List<List<Pair<Float, Float>>>? = null,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int
    ): Symbol? {
        val textMeasurer = textMeasurerState.value ?: return null

        val layout = style.layout ?: return null
        val paint = style.paint ?: return null

        val templateTextField = layout.textField?.processAsString(featureProperties, actualZoom) ?: return null
        val textField = substituteTemplate(templateTextField, featureProperties)
        if (textField.isBlank() || textField.length > 256) {
            return null
        }

        val fontSize = (layout.textSize.processAsFloat(featureProperties, actualZoom) ?: 16f)
        val textMaxWidth =
            layout.textMaxWidth.processAsFloat(featureProperties, actualZoom) ?: Float.POSITIVE_INFINITY

        val textColor = paint.textColor?.processAsColor(featureProperties, actualZoom) ?: Color.Black
        val textOpacity = paint.textOpacity.processAsFloat(featureProperties, actualZoom) ?: 1f
        if (textOpacity == 0f) {
            return null
        }

        val textHaloColor = paint.textHaloColor?.processAsColor(featureProperties, actualZoom) ?: Color.White
        val textHaloWidth = (paint.textHaloWidth.processAsFloat(featureProperties, actualZoom) ?: 0f)
        val offsetArr = layout.textOffset.processAsDoubleList(featureProperties, actualZoom) ?: listOf(0.0, 0.0)
        val anchor = TextAnchor.fromAny(layout.textAnchor?.processAsString(featureProperties, actualZoom)) ?: TextAnchor.Center

        val dx = (offsetArr.getOrNull(0) ?: 0.0).toFloat() * fontSize
        val dy = (offsetArr.getOrNull(1) ?: 0.0).toFloat() * fontSize

        val textStyle = TextStyle(
            color = textColor.copy(alpha = textOpacity),
            fontSize = fontSize.sp,
            textAlign = TextAlign.Unspecified,
            shadow = if (textHaloWidth > 0f) {
                Shadow(
                    color = textHaloColor,
                    offset = Offset.Zero,
                    blurRadius = textHaloWidth * 2
                )
            } else null
        )

        val textCacheKey = "TEXT-$textField-${fontSize}-${textColor.toArgb()}-${textHaloWidth}-${textHaloColor.toArgb()}-${density.density}"
        val textLayoutResult = mutex.withLock {
            pathCache.get(textCacheKey) as? TextLayoutResult
        } ?: textMeasurer.measure(
            text = AnnotatedString(textField),
            density = density,
            style = textStyle,
            maxLines = 1,
            constraints = Constraints(),
            softWrap = true
        ).also {
            mutex.withLock {
                pathCache.put(textCacheKey, it)
            }
        }

        val textWidth = textLayoutResult.size.width.toFloat()
        val textHeight = textLayoutResult.size.height.toFloat()
        val verticalGap = 1.1f * density.density

        if (lineStrings != null && lineStrings.isNotEmpty()) {
            return produceLineText(
                id = id,
                lineStrings = lineStrings,
                layout = layout,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                textWidth = textWidth,
                dx = dx,
                dy = dy,
                tileX = tileX,
                tileY = tileY,
                textHeight = textHeight,
                textField = textField,
                zoom = zoom,
                canvasSize = canvasSize,
                textLayoutResult = textLayoutResult,
                density = density,
                layerIndex = layerIndex
            )
        } else {
            return producePointText(
                id = id,
                placement = placement,
                anchor = anchor,
                textWidth = textWidth,
                textHeight = textHeight,
                dx = dx,
                dy = dy,
                tileX = tileX,
                tileY = tileY,
                zoom = zoom,
                canvasSize = canvasSize,
                layout = layout,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                textLayoutResult = textLayoutResult,
                textField = textField,
                density = density,
                layerIndex = layerIndex
            )
        }
    }

    private fun produceLineText(
        id: String,
        lineStrings: List<List<Pair<Float, Float>>>,
        layout: SymbolLayout,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        textWidth: Float,
        dx: Float,
        dy: Float,
        tileX: Int,
        tileY: Int,
        textHeight: Float,
        textField: String,
        zoom: Double,
        canvasSize: Int,
        textLayoutResult: TextLayoutResult,
        density: Density,
        layerIndex: Int,
    ): Symbol? {
        val symbolSpacing = layout.symbolSpacing.processAsFloat(featureProperties, actualZoom) ?: 250f
        val maxAngleDeg = layout.textMaxAngle.processAsFloat(featureProperties, actualZoom) ?: 45f
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: 0f
        val textViewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = false  // "auto" + line placement = map-aligned
        )
        val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: 2f) * density.density
        var anyPlaced = false
        var maxLength = 0f
        var maxLine: List<Pair<Float, Float>>? = null

        lineStrings.forEachIndexed lineStrings@{ indexLine, line ->
            if (line.size < 2) return@lineStrings
            val lineLength = line.zipWithNext { a, b ->
                val dx = a.first - b.first
                val dy = a.second - b.second
                sqrt(dx * dx + dy * dy)
            }.sum()

            if (lineLength > maxLength) {
                maxLength = lineLength
                maxLine = line
            }

            if (lineLength < textWidth) return@lineStrings
            val placements = LineLabelPlacement.calculatePlacements(line, textWidth, symbolSpacing, maxAngleDeg)
            if (placements.isNotEmpty()) anyPlaced = true
            placements.forEachIndexed { index, (pos, angle) ->
                val distToStart =
                    sqrt((pos.first - line.first().first).pow(2) + (pos.second - line.first().second).pow(2))
                val distToEnd =
                    sqrt((pos.first - line.last().first).pow(2) + (pos.second - line.last().second).pow(2))
                if (distToStart < textWidth / 2 || distToEnd < textWidth / 2) return@forEachIndexed
                val x = pos.first + dx
                val y = pos.second + dy
                val displayAngle = makeTextUpright(angle)

                try {
                    val normalizedPoint =
                        tileCoordToNormalized(tileX, tileY, x.toDouble(), y.toDouble(), zoom, canvasSize)
                    val globalX = normalizedPoint.x
                    val globalY = normalizedPoint.y

                    val leftBound = (x.toDouble() - textWidth.toDouble() / 2.0).toFloat() - textPadding
                    val topBound = (y.toDouble() - textHeight.toDouble() / 2.0).toFloat() - textPadding
                    val rightBound = (x.toDouble() + textWidth.toDouble() / 2.0).toFloat() + textPadding
                    val bottomBound = (y.toDouble() + textHeight.toDouble() / 2.0).toFloat() + textPadding

                    // Create a deterministic ID based on a tile, coordinates and indices
                    val coordHash = "${x.toInt()}_${y.toInt()}_${displayAngle.toInt()}"
                    val stableId = "L${tileX}_${tileY}_${id}_${indexLine}_${index}_$coordHash"

                    val lineTextAngle = displayAngle + textRotateDeg
                    return LabelPlacement(
                        text = textField,
                        position = ObbPoint(x, y),
                        angle = lineTextAngle,
                        bounds = Rect(
                            left = leftBound,
                            top = topBound,
                            right = rightBound,
                            bottom = bottomBound
                        ),
                        obb = OBB(
                            ObbPoint(x, y),
                            ovh.plrapps.mapcompose.vector.utils.obb.Size(textWidth + 2 * textPadding, textHeight + 2 * textPadding),
                            lineTextAngle
                        ),
                        layerIndex = layerIndex,
                        inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
                        overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
                        ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
                            ?: false
                    ).let { labelPlacement ->
                        Symbol.Text(
                            id = stableId,
                            global = Point(globalX, globalY),
                            placement = CompoundLabelPlacement(
                                spritePlacement = labelPlacement,
                                textPlacement = labelPlacement
                            ),
                            value = textLayoutResult,
                            viewportAligned = textViewportAligned,
                        )
                    }
                } catch (_: IllegalArgumentException) {
                    return@forEachIndexed
                }
            }
        }

        if (!anyPlaced && maxLine != null && maxLine.size >= 2) {
            val maxLineRef = maxLine // for smart cast
            val maxLineLength = maxLineRef.zipWithNext { a, b ->
                val dx = a.first - b.first
                val dy = a.second - b.second
                sqrt(dx * dx + dy * dy)
            }.sum()

            if (maxLineLength >= textWidth) {
                val midIdx = maxLineRef.size / 2
                val p1 = maxLineRef[midIdx - 1]
                val p2 = maxLineRef[midIdx]
                val x = (p1.first + p2.first) / 2 + dx
                val y = (p1.second + p2.second) / 2 + dy
                val angle = atan2(p2.second - p1.second, p2.first - p1.first) * 180f / PI.toFloat()
                val displayAngle = makeTextUpright(angle)
                val distToStart = sqrt((x - maxLineRef.first().first).pow(2) + (y - maxLineRef.first().second).pow(2))
                val distToEnd = sqrt((x - maxLineRef.last().first).pow(2) + (y - maxLineRef.last().second).pow(2))
                if (distToStart >= textWidth / 2 && distToEnd >= textWidth / 2) {
                    try {
                        val normalizedPoint =
                            tileCoordToNormalized(tileX, tileY, x.toDouble(), y.toDouble(), zoom, canvasSize)
                        val globalX = normalizedPoint.x
                        val globalY = normalizedPoint.y

                        val leftBound = (x.toDouble() - textWidth.toDouble() / 2.0).toFloat() - textPadding
                        val topBound = (y.toDouble() - textHeight.toDouble() / 2.0).toFloat() - textPadding
                        val rightBound = (x.toDouble() + textWidth.toDouble() / 2.0).toFloat() + textPadding
                        val bottomBound = (y.toDouble() + textHeight.toDouble() / 2.0).toFloat() + textPadding
                        val lineTextAngle = displayAngle + textRotateDeg
                        return LabelPlacement(
                            text = textField,
                            position = ObbPoint(x, y),
                            angle = lineTextAngle,
                            bounds = Rect(
                                left = leftBound,
                                top = topBound,
                                right = rightBound,
                                bottom = bottomBound
                            ),
                            obb = OBB(
                                ObbPoint(x, y),
                                ovh.plrapps.mapcompose.vector.utils.obb.Size(textWidth + 2 * textPadding, textHeight + 2 * textPadding),
                                lineTextAngle
                            ),
                            layerIndex = layerIndex,
                            inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
                            overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
                            ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
                                ?: false
                        ).let { labelPlacement ->
                            // Create a deterministic ID for the fallback case
                            val coordHash = "${x.toInt()}_${y.toInt()}_${displayAngle.toInt()}"
                            val stableId = "LF${tileX}_${tileY}_${id}_$coordHash"

                            Symbol.Text(
                                id = stableId,
                                global = Point(globalX, globalY),
                                placement = CompoundLabelPlacement(
                                    spritePlacement = labelPlacement,
                                    textPlacement = labelPlacement
                                ),
                                value = textLayoutResult,
                                viewportAligned = textViewportAligned,
                            )
                        }
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                }
            }
            return null
        }
        return null
    }

    private fun producePointText(
        placement: SymbolPlacement,
        anchor: TextAnchor,
        textWidth: Float,
        textHeight: Float,
        dx: Float,
        dy: Float,
        tileX: Int,
        tileY: Int,
        zoom: Double,
        canvasSize: Int,
        layout: SymbolLayout,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        textLayoutResult: TextLayoutResult,
        textField: String,
        density: Density,
        id: String,
        layerIndex: Int
    ): Symbol? {
        val x = placement.position.x + dx
        val y = placement.position.y + dy
        val textPosition = when (anchor) {
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

        try {
            val normalizedPoint = tileCoordToNormalized(
                tileX = tileX,
                tileY = tileY,
                pixelX = textPosition.x.toDouble(),
                pixelY = textPosition.y.toDouble(),
                zoom = zoom,
                tileSize = canvasSize
            )
            val globalX = normalizedPoint.x
            val globalY = normalizedPoint.y

            val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: 2f) * density.density

            val leftBound = (textPosition.x.toDouble() - textWidth.toDouble() / 2.0).toFloat() - textPadding
            val topBound = (textPosition.y.toDouble() - textHeight.toDouble() / 2.0).toFloat() - textPadding
            val rightBound = (textPosition.x.toDouble() + textWidth.toDouble() / 2.0).toFloat() + textPadding
            val bottomBound = (textPosition.y.toDouble() + textHeight.toDouble() / 2.0).toFloat() + textPadding

            val bounds = Rect(
                left = leftBound,
                top = topBound,
                right = rightBound,
                bottom = bottomBound
            )

            val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: 0f
            val pointTextAngle = placement.angle + textRotateDeg
            return LabelPlacement(
                text = textField,
                position = ObbPoint(textPosition.x, textPosition.y),
                angle = pointTextAngle,
                bounds = bounds,
                obb = OBB(
                    center = ObbPoint(textPosition.x, textPosition.y),
                    size = ovh.plrapps.mapcompose.vector.utils.obb.Size(textWidth + 2 * textPadding, textHeight + 2 * textPadding),
                    rotation = pointTextAngle
                ),
                layerIndex = layerIndex,
                inLayerPriority = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0,
                overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
                ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom) ?: false
            ).let { labelPlacement ->
                // Add coordinates to ID for uniqueness
                val coordHash = "${textPosition.x.toInt()}_${textPosition.y.toInt()}"
                val stableId = "P${tileX}_${tileY}_${id}_$coordHash"

                val textViewportAligned = resolveViewportAligned(
                    layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
                    defaultViewportAligned = true
                )
                Symbol.Text(
                    id = stableId,
                    global = Point(globalX, globalY),
                    placement = CompoundLabelPlacement(
                        spritePlacement = labelPlacement,
                        textPlacement = labelPlacement   // Correct placement for text
                    ),
                    value = textLayoutResult,
                    viewportAligned = textViewportAligned,
                )
            }
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    suspend fun produceSymbol(
        feature: Tile.Feature,
        style: SymbolLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        id: String,
        tileX: Int = 0,
        tileY: Int = 0,
        density: Density,
        layerIndex: Int = 0,
    ): List<Symbol> {
        val layout = style.layout ?: return emptyList()
        val paint = style.paint ?: return emptyList()

        // Calculate the symbol placement
        val placement: SymbolPlacement?
        var lineStrings: List<List<Pair<Float, Float>>>? = null
        if (feature.type == Tile.GeomType.POINT) {
            placement = calculatePointPlacement(feature = feature, extent = extent, canvasSize = canvasSize)
        } else if (layout.symbolPlacement?.processAsString(featureProperties, actualZoom) == "line" &&
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
        if (placement == null) return emptyList()

        // Check if there is both a sprite and text
        val hasSprite = layout.iconImage != null && spriteManager != null
        val hasText = layout.textField != null

        if (hasSprite && hasText && feature.type == Tile.GeomType.POINT && lineStrings == null) {
            // Create a SpriteWithText combo symbol for point objects
            produceSpriteWithText(
                id = "ST${tileX}_${tileY}_${id}",
                placement = placement,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                zoom = zoom,
                canvasSize = canvasSize,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex
            )?.let { return listOf(it) }
        }

        // For lines or when there is only one element - create separate symbols
        val list = mutableListOf<Symbol>()

        if (hasSprite) {
            produceSprite(
                id = "S${tileX}_${tileY}_${id}",
                placement = placement,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                zoom = zoom,
                canvasSize = canvasSize,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex
            )?.let { list.add(it) }
        }

        if (hasText) {
            produceText(
                id = "T${tileX}_${tileY}_${id}",
                placement = placement,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                zoom = zoom,
                lineStrings = lineStrings,
                canvasSize = canvasSize,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex
            )?.let { list.add(it) }
        }

        return list
    }
}
