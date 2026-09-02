package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SDF
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.collision.LabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.collision.LineLabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.collision.OverlapMode
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFormatted
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsImageName
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsBoolean
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsStringList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.renderer.utils.clipLine
import ovh.plrapps.mapcompose.vector.renderer.utils.drawStretchedImage
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite as SpriteInfo
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.renderer.utils.anchorAlignment
import ovh.plrapps.mapcompose.vector.renderer.utils.anchorCenterOffset
import ovh.plrapps.mapcompose.vector.renderer.utils.iconTextFitSize
import ovh.plrapps.mapcompose.vector.renderer.utils.isInsideTile
import ovh.plrapps.mapcompose.vector.renderer.utils.radialOffsetEms
import ovh.plrapps.mapcompose.vector.renderer.utils.variableAnchorOffsets
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsAnyList
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import kotlin.collections.zipWithNext
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
    /**
     * An icon.
     *
     * [drawSize] is the size the icon is drawn at -- `icon-size` applied to the sprite's *layout*
     * size, and `icon-text-fit` on top of that. It is deliberately not read back off the placement's
     * bounds, which carry `icon-padding`: padding widens the collision box, it does not stretch the
     * icon.
     */
    class Sprite(
        id: String,
        global: Point,
        placement: CompoundLabelPlacement,
        val value: ImageBitmap,
        val spriteMeta: SpriteInfo,
        val drawSize: IntSize,
        val opacity: Float = 1f,
        viewportAligned: Boolean = true,
    ) : Symbol(id, global, placement, viewportAligned = viewportAligned)

    class Text(
        id: String,
        global: Point,
        placement: CompoundLabelPlacement,
        val value: LabelArt,
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
        val spriteMeta: SpriteInfo,
        val text: LabelArt,
        val spriteSize: IntSize,
        val textSize: IntSize,
        val verticalGap: Float,
        val iconOptional: Boolean,
        val textOptional: Boolean,
        val iconOpacity: Float = 1f,
        viewportAligned: Boolean = true,
        val textCandidates: List<TextPlacementCandidate> = emptyList(),
        /** `icon-text-fit`: the icon was stretched around the label, so the label sits inside it. */
        val textInsideIcon: Boolean = false,
    ) : Symbol(id, global, placement, align, viewportAligned) {

        /** This symbol's icon on its own, for when the text could not be placed. */
        fun iconOnly(id: String, placement: CompoundLabelPlacement): Sprite = Sprite(
            id = id,
            global = global,
            placement = placement,
            value = sprite,
            spriteMeta = spriteMeta,
            drawSize = spriteSize,
            opacity = iconOpacity,
            viewportAligned = viewportAligned,
        )

        /** This symbol's label on its own, for when the icon could not be placed. */
        fun textOnly(
            id: String,
            global: Point,
            placement: CompoundLabelPlacement,
            spriteAnchorGlobal: Point? = null,
            textOffset: Pair<Float, Float>? = null,
        ): Text = Text(
            id = id,
            global = global,
            placement = placement,
            value = text,
            viewportAligned = viewportAligned,
            spriteAnchorGlobal = spriteAnchorGlobal,
            textOffset = textOffset,
        )
    }

    fun draw(drawScope: DrawScope) {
        val s = getInPixels()
        val symbolCenter = Offset(s.width / 2f, s.height / 2f)
        with(drawScope) {
            when (this@Symbol) {
                is Sprite -> {
                    rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                        drawStretchedImage(
                            image = value,
                            sprite = spriteMeta,
                            dstOffset = Offset.Zero,
                            dstSize = Size(drawSize.width.toFloat(), drawSize.height.toFloat()),
                            alpha = opacity,
                        )
                    }
                }

                is Text -> {
                    // For text, use the corner from textPlacement (if available) or spritePlacement
                    val textAngle =
                        placement.textPlacement?.angle ?: placement.spritePlacement.angle
                    rotate(textAngle, pivot = symbolCenter) {
                        val centerX = (s.width - value.width) / 2f
                        val centerY = (s.height - value.height) / 2f
                        value.draw(this, Offset(centerX, centerY))
                    }
                }

                is SpriteWithText -> {
                    rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                        // For SpriteWithText offset is already taken into account in the position, you need to correctly place the elements inside the Canvas
                        // The sprite should be horizontally centered
                        val spriteLeft = (s.width - spriteSize.width) / 2f
                        val spriteTop = 0f

                        val textLeft = (s.width - textSize.width) / 2f
                        /* With icon-text-fit the icon was stretched *around* the label, so the label
                         * is centred on it; otherwise it hangs below, separated by the gap. */
                        val textTop = if (textInsideIcon) {
                            (s.height - textSize.height) / 2f
                        } else {
                            spriteSize.height + verticalGap
                        }

                        drawStretchedImage(
                            image = sprite,
                            sprite = spriteMeta,
                            dstOffset = Offset(spriteLeft, spriteTop),
                            dstSize = Size(spriteSize.width.toFloat(), spriteSize.height.toFloat()),
                            alpha = iconOpacity,
                        )

                        // Draw text under the sprite
                        text.draw(this, Offset(textLeft, textTop))
                    }
                }
            }
        }
    }

    fun getInPixels(): Size {
        return when (this) {
            is SpriteWithText -> if (textInsideIcon) {
                Size(spriteSize.width.toFloat(), spriteSize.height.toFloat())
            } else {
                val totalWidth = max(spriteSize.width, textSize.width)
                val totalHeight = (spriteSize.height + verticalGap + textSize.height)
                Size(totalWidth.toFloat(), totalHeight)
            }

            is Sprite -> Size(drawSize.width.toFloat(), drawSize.height.toFloat())

            is Text -> Size(value.width, value.height)
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
    private val geometryDecoders = GeometryDecoders()

    /** Shapes and rasterizes labels, from the style's glyph server where it has one. */
    private val labelBuilder = TextLabelBuilder(
        glyphManager = configuration.glyphManager,
        textMeasurerState = textMeasurerState,
        cache = pathCache,
        mutex = mutex,
    )

    /** Passed to every `icon-image` evaluation so `["image", ...]` can fall back to a present id. */
    private val availableImages: List<String>? = spriteManager?.availableImages

    private fun resolveViewportAligned(alignmentValue: String?, defaultViewportAligned: Boolean): Boolean =
        when (alignmentValue) {
            "map"      -> false
            "viewport" -> true
            else       -> defaultViewportAligned
        }

    private fun resolveIconOverlapMode(
        layout: SymbolLayout,
        props: EvalFeature?,
        zoom: Double
    ): OverlapMode =
        layout.iconOverlap?.processAsString(props, zoom)?.let { v ->
            when (v) {
                "always" -> OverlapMode.Always
                "cooperative" -> OverlapMode.Cooperative
                else -> OverlapMode.Never
            }
        } ?: if ((layout.iconAllowOverlap?.processAsBoolean(props, zoom) ?: StyleSpecDefaults.ICON_ALLOW_OVERLAP)) OverlapMode.Always else OverlapMode.Never

    private fun resolveTextOverlapMode(
        layout: SymbolLayout,
        props: EvalFeature?,
        zoom: Double
    ): OverlapMode =
        layout.textOverlap?.processAsString(props, zoom)?.let { v ->
            when (v) {
                "always" -> OverlapMode.Always
                "cooperative" -> OverlapMode.Cooperative
                else -> OverlapMode.Never
            }
        } ?: if ((layout.textAllowOverlap?.processAsBoolean(props, zoom) ?: StyleSpecDefaults.TEXT_ALLOW_OVERLAP)) OverlapMode.Always else OverlapMode.Never

    /**
     * How an SDF entry is recoloured, or `null` when the sprite is a plain image.
     *
     * `icon-color` and the `icon-halo-*` properties only mean anything for an SDF entry -- upstream
     * ignores them on a plain image, which is tinted by `icon-color` alone. An absent `icon-color`
     * falls back to the spec default rather than skipping the shading: the sprite has no colour of
     * its own, so leaving it unshaded would draw a raw distance field.
     */
    private fun sdfFor(
        spriteInfo: SpriteInfo,
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        iconScale: Float,
        density: Density,
    ): SDF? {
        if (!spriteInfo.sdf) return null
        return SDF(
            fillColor = paint.iconColor?.processAsColor(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_COLOR,
            haloColor = paint.iconHaloColor?.processAsColor(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_HALO_COLOR,
            haloWidth = paint.iconHaloWidth.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_HALO_WIDTH.toFloat(),
            haloBlur = paint.iconHaloBlur.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_HALO_BLUR.toFloat(),
            /* Drawn size over sheet size: the halo is given in layout pixels but the distance field
             * is measured in sheet pixels, and a hidpi sheet packs several of those per layout px. */
            fontScale = iconScale * density.density / spriteInfo.pixelRatio,
        )
    }

    /**
     * Transformation to normalized Mercator coordinates.
     *
     * [tileZ] is the zoom of the tile the coordinates belong to, which is *not* the zoom style
     * expressions are evaluated at: an overzoomed source is laid out over its ancestor, so
     * [tileX]/[tileY]/[tileZ] and [tileSize] describe the ancestor while the layer is evaluated at
     * the display zoom. That is upstream's `OverscaledTileID`, where the canonical id positions the
     * tile and `overscaledZ` parameterises its content.
     */
    private fun tileCoordToNormalized(
        tileX: Int,
        tileY: Int,
        pixelX: Double,
        pixelY: Double,
        tileZ: Double,
        tileSize: Int
    ): Point {
        val n = 2.0.pow(tileZ)

        val normalizedX = (tileX * tileSize + pixelX) / (tileSize * n)
        val normalizedY = (tileY * tileSize + pixelY) / (tileSize * n)

        return Point(normalizedX, normalizedY)
    }

    /**
     * Expands the legacy `{token}` syntax in a `text-field`.
     *
     * Upstream still rewrites these into a `concat` expression when it migrates a style, so they are
     * honoured here too. What is *not* honoured any more is the rule this used to apply first: a
     * `text-field` with no braces at all was replaced wholesale by the feature's `name`, so a style
     * asking for a literal label got the feature's name instead.
     *
     * `{name}` prefers the configured language's `name:xx`, which is how a multilingual vector
     * source is localised without the style naming a language.
     */
    private fun substituteTokens(template: String, properties: Map<String, Any?>?): String {
        if (properties == null) return template
        val lang = configuration.lang?.code

        return regexForSubProcess.replace(template) { matchResult ->
            val key = matchResult.groupValues[1]
            if (lang != null && (key == "name" || key == "name:$lang")) {
                val localized = properties["name:$lang"]?.toString()
                if (!localized.isNullOrBlank()) return@replace localized
                return@replace properties["name"]?.toString() ?: ""
            }
            properties[key]?.toString() ?: ""
        }
    }

    /**
     * One placement per point of the feature.
     *
     * A MultiPoint is several symbols, not one: upstream's `symbol_layout` iterates every point of
     * every ring. Only the first used to be read, so a feature carrying a handful of stops produced
     * a single label.
     *
     * A point outside the tile is dropped, which is upstream's `addSymbolAtAnchor`: *"Symbol layers
     * are drawn across tile boundaries, We filter out symbols outside our tile boundaries (which may
     * be included in vector tile buffers) to prevent double-drawing symbols."*
     */
    private fun calculatePointPlacements(
        feature: Tile.Feature,
        extent: Int,
        canvasSize: Int
    ): List<SymbolPlacement> =
        geometryDecoders.decodePoint(geometry = feature.geometry, extent = extent, canvasSize = canvasSize)
            .filter { isInsideTile(it.x, it.y, canvasSize) }
            .map { SymbolPlacement(position = ObbPoint(it.x.toFloat(), it.y.toFloat()), angle = 0f) }

    private val regexForSubProcess = "\\{([^}]+)\\}".toRegex()

    private fun subProcess(
        input: String,
        featureProperties: EvalFeature?
    ): String {
        return if (input.firstOrNull() == '{') {
            val matchResult = regexForSubProcess.find(input)
            if (matchResult != null) {
                val key = matchResult.groupValues[1]
                val propValue = featureProperties?.properties?.get(key)?.toString() ?: ""
                input.replace("{$key}", propValue)
            } else {
                input
            }
        } else {
            input
        }
    }

    // region resolved style

    /**
     * Every `text-*` property, evaluated once for a feature.
     *
     * [anchor] only matters for `text-justify: auto`, which upstream resolves against the anchor so
     * that a right-anchored label reads right-aligned.
     */
    private fun resolvedTextStyle(
        layout: SymbolLayout,
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
        anchor: TextAnchor,
    ): ResolvedTextStyle {
        val fontSize = (layout.textSize.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_SIZE.toFloat()) * density.density
        val justify = layout.textJustify?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_JUSTIFY
        return ResolvedTextStyle(
            fontStack = layout.textFont?.processAsStringList(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_FONT,
            fontSize = fontSize,
            color = paint.textColor?.processAsColor(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_COLOR,
            opacity = paint.textOpacity.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_OPACITY.toFloat(),
            haloColor = paint.textHaloColor?.processAsColor(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_HALO_COLOR,
            haloWidth = (paint.textHaloWidth.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_HALO_WIDTH.toFloat()) * density.density,
            haloBlur = (paint.textHaloBlur.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_HALO_BLUR.toFloat()) * density.density,
            letterSpacing = layout.textLetterSpacing.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_LETTER_SPACING.toFloat(),
            lineHeight = layout.textLineHeight.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_LINE_HEIGHT.toFloat(),
            maxWidth = layout.textMaxWidth.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_MAX_WIDTH.toFloat(),
            justify = TextLabelBuilder.resolveJustify(justify, anchor.value),
            transform = layout.textTransform?.processAsString(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_TRANSFORM,
            writingMode = layout.textWritingMode?.processAsStringList(featureProperties, actualZoom),
        )
    }

    /**
     * The label's text, as an expression-evaluated [Formatted].
     *
     * The legacy `{token}` syntax is still expanded, because styles in the wild use it and upstream
     * rewrites it too -- but only where the tokens actually appear. A literal `text-field` is now
     * rendered as written; it used to be silently replaced by the feature's `name`.
     */
    private fun textFieldOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): Formatted? {
        val formatted = layout.textField.processAsFormatted(featureProperties, actualZoom, availableImages)
            ?: return null
        if (formatted.isEmpty()) return null
        val properties = featureProperties?.properties
        val sections = formatted.sections.map { section ->
            if (section.text.contains('{')) {
                section.copy(text = substituteTokens(section.text, properties))
            } else {
                section
            }
        }
        return Formatted(sections)
    }

    private suspend fun buildLabel(
        layout: SymbolLayout,
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
        anchor: TextAnchor,
    ): Pair<LabelArt, ResolvedTextStyle>? {
        val formatted = textFieldOf(layout, featureProperties, actualZoom) ?: return null
        val style = resolvedTextStyle(layout, paint, featureProperties, actualZoom, density, anchor)
        if (style.opacity <= 0f) return null
        val art = labelBuilder.build(formatted, style, density) ?: return null
        return art to style
    }

    private fun textAnchorOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): TextAnchor = TextAnchor.fromAny(
        layout.textAnchor?.processAsString(featureProperties, actualZoom)
    ) ?: TextAnchor.fromString(StyleSpecDefaults.TEXT_ANCHOR)

    private fun iconAnchorOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): TextAnchor = TextAnchor.fromAny(
        layout.iconAnchor?.processAsString(featureProperties, actualZoom)
    ) ?: TextAnchor.fromString(StyleSpecDefaults.ICON_ANCHOR)

    /**
     * `text-offset` in device pixels, for one anchor.
     *
     * The three offset properties are mutually exclusive and checked in upstream's order:
     * `text-variable-anchor-offset` names an offset per anchor, `text-radial-offset` puts the label
     * a fixed distance away *along* the anchor's direction, and `text-offset` is a plain vector.
     * All three are specified in ems.
     */
    private fun textOffsetPx(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        anchor: TextAnchor,
        fontSizePx: Float,
    ): Offset {
        val perAnchor = variableAnchorOffsets(
            layout.textVariableAnchorOffset.processAsAnyList(featureProperties, actualZoom)
        )
        perAnchor[anchor]?.let { return it * fontSizePx }

        val radial = layout.textRadialOffset.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_RADIAL_OFFSET.toFloat()
        if (radial != 0f) return radialOffsetEms(anchor, radial) * fontSizePx

        val offset = layout.textOffset.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_OFFSET
        return Offset(
            (offset.getOrNull(0) ?: 0.0).toFloat() * fontSizePx,
            (offset.getOrNull(1) ?: 0.0).toFloat() * fontSizePx,
        )
    }

    /**
     * `icon-translate` / `text-translate`, in device pixels.
     *
     * The offset is applied to the symbol's position before it is converted to map coordinates,
     * which is the same thing [ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate] does for
     * the tile painters.
     *
     * **Divergence:** `*-translate-anchor: viewport` is read but inert, exactly as it is for
     * `fill`, `line` and `circle`. Upstream counter-rotates a viewport-anchored offset by the map's
     * bearing; symbols are laid out before the bearing is known, so a `viewport` anchor applies the
     * same unrotated offset as `map` and differs from upstream only on a rotated map.
     */
    private fun iconTranslatePx(
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
    ): Offset = translatePx(
        paint.iconTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.ICON_TRANSLATE,
        density,
    )

    /** See [iconTranslatePx]; `text-translate-anchor` carries the same divergence. */
    private fun textTranslatePx(
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
    ): Offset = translatePx(
        paint.textTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_TRANSLATE,
        density,
    )

    private fun translatePx(translate: List<Double>, density: Density): Offset = Offset(
        (translate.getOrNull(0) ?: 0.0).toFloat() * density.density,
        (translate.getOrNull(1) ?: 0.0).toFloat() * density.density,
    )

    /** `icon-offset`, in the icon's own pixels and so scaled the same way its size is. */
    private fun iconOffsetPx(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        scale: Float,
    ): Offset {
        val offset = layout.iconOffset.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.ICON_OFFSET
        return Offset(
            (offset.getOrNull(0) ?: 0.0).toFloat() * scale,
            (offset.getOrNull(1) ?: 0.0).toFloat() * scale,
        )
    }

    /**
     * The feature's `symbol-placement`, for a caller that has to group line features before layout.
     *
     * Upstream reads it once per layer; it is evaluated per feature here because this port lets a
     * layout property be data-driven throughout.
     */
    fun symbolPlacementOf(
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): String = style.layout?.let { placementModeOf(it, featureProperties, actualZoom) }
        ?: StyleSpecDefaults.SYMBOL_PLACEMENT

    /**
     * The plain text of `text-field`, which is the key upstream's `mergeLines` stitches lines on.
     *
     * Null when the layer has no label at all -- such a feature takes no part in a merge.
     */
    fun mergeTextOf(
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): String? = style.layout
        ?.let { textFieldOf(it, featureProperties, actualZoom) }
        ?.sections?.joinToString("") { it.text }
        ?.takeIf { it.isNotEmpty() }

    /** The feature's lines in canvas pixels, before clipping and merging. */
    fun decodeLines(
        feature: Tile.Feature,
        extent: Int,
        canvasSize: Int,
    ): List<List<Pair<Float, Float>>>? = when (feature.type) {
        Tile.GeomType.LINESTRING ->
            geometryDecoders.decodeLine(feature.geometry, extent = extent, canvasSize = canvasSize)

        Tile.GeomType.POLYGON ->
            geometryDecoders.decodePolygons(feature.geometry, extent = extent, canvasSize = canvasSize)
                .flatten()

        else -> null
    }

    private fun placementModeOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): String = layout.symbolPlacement?.processAsString(featureProperties, actualZoom)
        ?: StyleSpecDefaults.SYMBOL_PLACEMENT

    // endregion

    // region label placement building

    /**
     * A collision box around a centre.
     *
     * `*-padding` widens the box only -- it must never reach the drawn size, which is what made a
     * padded icon render larger than the style asked for.
     */
    private fun labelPlacementOf(
        text: String,
        center: ObbPoint,
        width: Float,
        height: Float,
        padding: Float,
        angle: Float,
        layerIndex: Int,
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        overlapMode: OverlapMode,
        ignorePlacement: Boolean,
    ): LabelPlacement = LabelPlacement(
        text = text,
        position = center,
        angle = angle,
        bounds = Rect(
            left = center.x - width / 2f - padding,
            top = center.y - height / 2f - padding,
            right = center.x + width / 2f + padding,
            bottom = center.y + height / 2f + padding,
        ),
        obb = OBB(
            center,
            ObbSize(width + 2 * padding, height + 2 * padding),
            angle,
        ),
        layerIndex = layerIndex,
        inLayerPriority = sortKeyOf(layout, featureProperties, actualZoom),
        overlapMode = overlapMode,
        ignorePlacement = ignorePlacement,
        zOrder = layout.symbolZOrder?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.SYMBOL_Z_ORDER,
        /* `symbol-z-order: auto` means "sort-key if the layer has one, viewport-y otherwise", so
         * whether the property was *set* matters, not just what it evaluates to. */
        hasSortKey = layout.symbolSortKey != null,
    )

    private fun sortKeyOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): Double = layout.symbolSortKey.processAsDouble(featureProperties, actualZoom) ?: 0.0

    /**
     * Whether a box that reaches outside the tile must be dropped.
     *
     * `symbol-avoid-edges` exists because a label crossing a tile boundary is laid out twice, once
     * per tile, and the two copies cannot see each other's collision boxes.
     */
    private fun avoidsEdges(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): Boolean = layout.symbolAvoidEdges?.processAsBoolean(featureProperties, actualZoom)
        ?: StyleSpecDefaults.SYMBOL_AVOID_EDGES

    private fun crossesTileEdge(bounds: Rect, canvasSize: Int): Boolean =
        bounds.left < 0f || bounds.top < 0f ||
            bounds.right > canvasSize.toFloat() || bounds.bottom > canvasSize.toFloat()

    // endregion

    /**
     * Builds one icon symbol.
     *
     * `icon-anchor` and `icon-offset` move the icon's box relative to the feature's point;
     * `icon-padding` widens its collision box without changing what is drawn.
     */
    private fun produceSprite(
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
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
            layout.iconImage.processAsImageName(featureProperties, actualZoom, availableImages)
                ?.let { subProcess(it, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId)
            ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_SIZE.toFloat()
        val iconColor = paint.iconColor?.processAsColor(featureProperties, actualZoom)
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_OPACITY.toFloat()
        if (iconOpacity <= 0f) {
            return null
        }
        val scale = iconScale * density.density

        val sdf = sdfFor(spriteInfo, paint, featureProperties, actualZoom, iconScale, density)

        val spritePair = spriteManager.getSprite(spriteId, iconColor, sdf)
        if (spritePair == null) {
            return null
        }
        val (spriteMeta, sprite) = spritePair

        val size = IntSize(
            (spriteMeta.layoutWidth * scale).toInt(),
            (spriteMeta.layoutHeight * scale).toInt()
        )
        if (size.width <= 0 || size.height <= 0) return null

        val anchorOffset = anchorCenterOffset(
            iconAnchorOf(layout, featureProperties, actualZoom),
            size.width.toFloat(),
            size.height.toFloat(),
        )
        val offset = iconOffsetPx(layout, featureProperties, actualZoom, scale)
        val translate = iconTranslatePx(paint, featureProperties, actualZoom, density)
        val spritePosition = ObbPoint(
            placement.position.x + anchorOffset.x + offset.x + translate.x,
            placement.position.y + anchorOffset.y + offset.y + translate.y,
        )

        // Direct conversion of tile coordinates to normalized MapCompose coordinates
        val normalizedPoint = tileCoordToNormalized(
            tileX = tileX,
            tileY = tileY,
            pixelX = spritePosition.x.toDouble(),
            pixelY = spritePosition.y.toDouble(),
            tileZ = tileZ,
            tileSize = canvasSize
        )

        val iconPadding = (layout.iconPadding.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_PADDING.toFloat()) * density.density
        val isLinePlacement = placementModeOf(layout, featureProperties, actualZoom).isLinePlacement()
        val keepUpright = layout.iconKeepUpright?.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.ICON_KEEP_UPRIGHT
        val iconRotateDeg = layout.iconRotate.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_ROTATE.toFloat()
        val baseAngle = if (isLinePlacement && keepUpright) makeTextUpright(placement.angle) else placement.angle
        val spriteAngle = baseAngle + iconRotateDeg

        val labelPlacement = labelPlacementOf(
            text = "sprite_$spriteId",
            center = spritePosition,
            width = size.width.toFloat(),
            height = size.height.toFloat(),
            padding = iconPadding,
            angle = spriteAngle,
            layerIndex = layerIndex,
            layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
            overlapMode = resolveIconOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.iconIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_IGNORE_PLACEMENT,
        )
        if (avoidsEdges(layout, featureProperties, actualZoom) &&
            crossesTileEdge(labelPlacement.bounds, canvasSize)
        ) {
            return null
        }

        val iconViewportAligned = resolveViewportAligned(
            layout.iconRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = !isLinePlacement
        )
        return Symbol.Sprite(
            id = id,
            global = Point(normalizedPoint.x, normalizedPoint.y),
            placement = CompoundLabelPlacement(labelPlacement, null),
            value = sprite,
            spriteMeta = spriteMeta,
            drawSize = size,
            opacity = iconOpacity,
            viewportAligned = iconViewportAligned,
        )
    }

    /**
     * Sets the angle of the caption to the range where the text always reads from left to right (or bottom to top for vertical lines).
     * If the angle is outside [-90, 90] degrees, it is flipped 180°.
     * This prevents the text from appearing upside down on the lines.
     *
     * Applied only when `text-keep-upright` / `icon-keep-upright` asks for it; it used to be
     * unconditional, which flipped labels a style had deliberately left free to rotate.
     * @param angle the original angle (in degrees)
     * @return the angle to display the text correctly
     */
    private fun makeTextUpright(angle: Float): Float {
        return if (angle > 90f || angle < -90f) angle + 180f else angle
    }

    /**
     * Builds the combined icon-and-label symbol for a point feature.
     *
     * With `icon-text-fit` the label sits *inside* the icon, which is stretched around it; without
     * it the label hangs below the icon and `text-anchor` / `text-variable-anchor` decide where.
     */
    private suspend fun produceSpriteWithText(
        id: String,
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int
    ): Symbol? {
        val layout = style.layout ?: return null
        val paint = style.paint ?: return null
        val spriteManager = spriteManager ?: return null

        val spriteId =
            layout.iconImage.processAsImageName(featureProperties, actualZoom, availableImages)
                ?.let { subProcess(it, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId) ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_SIZE.toFloat()
        val iconColor = paint.iconColor?.processAsColor(featureProperties, actualZoom)
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_OPACITY.toFloat()
        if (iconOpacity <= 0f) return null

        val scale = iconScale * density.density

        val sdf = sdfFor(spriteInfo, paint, featureProperties, actualZoom, iconScale, density)
        val spritePair = spriteManager.getSprite(spriteId, iconColor, sdf) ?: return null
        val (spriteMeta, sprite) = spritePair

        val anchor = textAnchorOf(layout, featureProperties, actualZoom)
        val (textArt, textStyle) = buildLabel(layout, paint, featureProperties, actualZoom, density, anchor)
            ?: return null

        val iconWidth = spriteMeta.layoutWidth * scale
        val iconHeight = spriteMeta.layoutHeight * scale
        val fit = layout.iconTextFit?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.ICON_TEXT_FIT
        val fitPadding = (layout.iconTextFitPadding.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.ICON_TEXT_FIT_PADDING).map { it * density.density }
        val fitted = iconTextFitSize(
            fit = fit,
            sprite = spriteMeta,
            iconWidth = iconWidth,
            iconHeight = iconHeight,
            textWidth = textArt.width,
            textHeight = textArt.height,
            padding = fitPadding,
        )
        val textInsideIcon = fit != StyleSpecDefaults.ICON_TEXT_FIT

        val spriteSize = IntSize(fitted.width.toInt(), fitted.height.toInt())
        if (spriteSize.width <= 0 || spriteSize.height <= 0) return null
        val textSize = IntSize(textArt.width.toInt(), textArt.height.toInt())

        val iconAnchorOffset = anchorCenterOffset(
            iconAnchorOf(layout, featureProperties, actualZoom),
            spriteSize.width.toFloat(),
            spriteSize.height.toFloat(),
        )
        val iconOffset = iconOffsetPx(layout, featureProperties, actualZoom, scale)
        val iconTranslate = iconTranslatePx(paint, featureProperties, actualZoom, density)
        val textTranslate = textTranslatePx(paint, featureProperties, actualZoom, density)
        val spritePosition = ObbPoint(
            placement.position.x + iconAnchorOffset.x + iconOffset.x + iconTranslate.x,
            placement.position.y + iconAnchorOffset.y + iconOffset.y + iconTranslate.y,
        )

        val normalizedPoint = tileCoordToNormalized(
            tileX = tileX,
            tileY = tileY,
            pixelX = spritePosition.x.toDouble(),
            pixelY = spritePosition.y.toDouble(),
            tileZ = tileZ,
            tileSize = canvasSize
        )

        val iconPadding = (layout.iconPadding.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_PADDING.toFloat()) * density.density
        val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density

        val iconRotateDeg = layout.iconRotate.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_ROTATE.toFloat()
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_ROTATE.toFloat()
        val textOverlap = resolveTextOverlapMode(layout, featureProperties, actualZoom)
        val textIgnorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_IGNORE_PLACEMENT
        val plainText = textArt.text

        val spriteLabelPlacement = labelPlacementOf(
            text = "sprite_$spriteId",
            center = spritePosition,
            width = spriteSize.width.toFloat(),
            height = spriteSize.height.toFloat(),
            padding = iconPadding,
            angle = iconRotateDeg,
            layerIndex = layerIndex,
            layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
            overlapMode = resolveIconOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.iconIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_IGNORE_PLACEMENT,
        )
        if (avoidsEdges(layout, featureProperties, actualZoom) &&
            crossesTileEdge(spriteLabelPlacement.bounds, canvasSize)
        ) {
            return null
        }

        /* With icon-text-fit the label is part of the icon, so it neither hangs below it nor takes
         * a position of its own; the gap and every anchor candidate collapse onto the icon. */
        val verticalGap = if (textInsideIcon) 0f else 2.0f * density.density
        val textPosition = if (textInsideIcon) {
            spritePosition
        } else {
            ObbPoint(
                spritePosition.x,
                spritePosition.y + spriteSize.height / 2f + verticalGap + textSize.height / 2f,
            )
        }

        val textLabelPlacement = labelPlacementOf(
            text = plainText,
            center = textPosition,
            width = textSize.width.toFloat(),
            height = textSize.height.toFloat(),
            padding = textPadding,
            angle = textRotateDeg,
            layerIndex = layerIndex,
            layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
            overlapMode = textOverlap,
            ignorePlacement = textIgnorePlacement,
        )

        val anchors: List<TextAnchor> = when {
            textInsideIcon -> listOf(TextAnchor.Center)
            else -> layout.textVariableAnchor?.processAsStringList(featureProperties, actualZoom)
                ?.takeIf { it.isNotEmpty() }
                ?.map { TextAnchor.fromString(it) }
                ?: listOf(anchor)
        }

        val textCandidates: List<TextPlacementCandidate> = anchors.map { candidateAnchor ->
            /* The label is placed by its box: the anchor names the side of the box that lands on
             * the point, and `text-offset` / `text-radial-offset` push it away from there. The icon
             * takes up room in between, so the box also clears the icon's half-size. */
            val anchorOffset = anchorCenterOffset(
                candidateAnchor,
                textSize.width.toFloat(),
                textSize.height.toFloat(),
            )
            val alignment = anchorAlignment(candidateAnchor)
            val clearance = if (textInsideIcon) {
                Offset.Zero
            } else {
                Offset(
                    (0.5f - alignment.x) * 2f * (spriteSize.width / 2f + verticalGap),
                    (0.5f - alignment.y) * 2f * (spriteSize.height / 2f + verticalGap),
                )
            }
            val userOffset = textOffsetPx(
                layout, featureProperties, actualZoom, candidateAnchor, textStyle.fontSize
            )
            val dx = anchorOffset.x + clearance.x + userOffset.x + textTranslate.x
            val dy = anchorOffset.y + clearance.y + userOffset.y + textTranslate.y
            val cx = spritePosition.x + dx
            val cy = spritePosition.y + dy
            val norm = tileCoordToNormalized(tileX, tileY, cx.toDouble(), cy.toDouble(), tileZ, canvasSize)
            TextPlacementCandidate(
                labelPlacement = labelPlacementOf(
                    text = plainText,
                    center = ObbPoint(cx, cy),
                    width = textSize.width.toFloat(),
                    height = textSize.height.toFloat(),
                    padding = textPadding,
                    angle = textRotateDeg,
                    layerIndex = layerIndex,
                    layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
                    overlapMode = textOverlap,
                    ignorePlacement = textIgnorePlacement,
                ),
                mercatorX = norm.x,
                mercatorY = norm.y,
                dx = dx,
                dy = dy,
            )
        }

        /* SymbolComposer positions a symbol by its box's centre; the drawn stack's own centre is
         * the icon's when the label is inside it, and the icon's centre within the stack otherwise. */
        val totalHeight = if (textInsideIcon) {
            spriteSize.height.toFloat()
        } else {
            spriteSize.height + verticalGap + textSize.height
        }
        val centerOffset = Offset(
            x = -0.5f,
            y = -((spriteSize.height.toFloat() / 2f) / totalHeight),
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
            spriteMeta = spriteMeta,
            text = textArt,
            spriteSize = spriteSize,
            textSize = textSize,
            verticalGap = verticalGap,
            iconOptional = layout.iconOptional?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_OPTIONAL,
            textOptional = layout.textOptional?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_OPTIONAL,
            iconOpacity = iconOpacity,
            viewportAligned = viewportAligned,
            textCandidates = textCandidates,
            textInsideIcon = textInsideIcon,
        )
    }

    /** Builds the label symbols of a feature that has text but no icon. */
    private suspend fun produceText(
        placement: SymbolPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        lineStrings: List<List<Pair<Float, Float>>>? = null,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int,
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>? = null,
    ): List<Symbol> {
        val layout = style.layout ?: return emptyList()
        val paint = style.paint ?: return emptyList()

        val anchor = textAnchorOf(layout, featureProperties, actualZoom)
        val (art, textStyle) = buildLabel(layout, paint, featureProperties, actualZoom, density, anchor)
            ?: return emptyList()

        val userOffset = textOffsetPx(layout, featureProperties, actualZoom, anchor, textStyle.fontSize)
        val offset = userOffset + textTranslatePx(paint, featureProperties, actualZoom, density)

        return if (lineStrings != null && lineStrings.isNotEmpty()) {
            produceLineText(
                id = id,
                lineStrings = lineStrings,
                layout = layout,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                dx = offset.x,
                dy = offset.y,
                tileX = tileX,
                tileY = tileY,
                art = art,
                tileZ = tileZ,
                canvasSize = canvasSize,
                density = density,
                layerIndex = layerIndex,
                fontSize = textStyle.fontSize,
                compareText = compareText,
            )
        } else {
            listOfNotNull(
                producePointText(
                    id = id,
                    placement = placement,
                    anchor = anchor,
                    dx = offset.x,
                    dy = offset.y,
                    tileX = tileX,
                    tileY = tileY,
                    tileZ = tileZ,
                    canvasSize = canvasSize,
                    layout = layout,
                    featureProperties = featureProperties,
                    actualZoom = actualZoom,
                    art = art,
                    density = density,
                    layerIndex = layerIndex
                )
            )
        }
    }

    /**
     * Labels along a line, one per `symbol-spacing` step.
     *
     * Every placement the walk finds is emitted; only the first used to be, so `symbol-spacing`
     * could never repeat a label along a long road however small the spacing was.
     */
    private fun produceLineText(
        id: String,
        lineStrings: List<List<Pair<Float, Float>>>,
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        dx: Float,
        dy: Float,
        tileX: Int,
        tileY: Int,
        art: LabelArt,
        tileZ: Double,
        canvasSize: Int,
        density: Density,
        layerIndex: Int,
        fontSize: Float,
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>?,
    ): List<Symbol> {
        val textWidth = art.width
        val textHeight = art.height
        val symbolSpacing = (layout.symbolSpacing.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.SYMBOL_SPACING.toFloat()) * density.density
        val maxAngleDeg = layout.textMaxAngle.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_MAX_ANGLE.toFloat()
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_ROTATE.toFloat()
        val keepUpright = layout.textKeepUpright?.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_KEEP_UPRIGHT
        val textViewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = false  // "auto" + line placement = map-aligned
        )
        val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density
        val avoidEdges = avoidsEdges(layout, featureProperties, actualZoom)
        val overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom)
        val ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_IGNORE_PLACEMENT
        val plainText = art.text

        val centerOnly = placementModeOf(layout, featureProperties, actualZoom) ==
            SYMBOL_PLACEMENT_LINE_CENTER

        val out = mutableListOf<Symbol>()

        lineStrings.forEachIndexed lineStrings@{ indexLine, line ->
            if (line.size < 2) return@lineStrings
            val lineLength = lineLengthOf(line)
            if (lineLength < textWidth) return@lineStrings

            val placements = if (centerOnly) {
                LineLabelPlacement.centerPlacement(line)?.let { listOf(it) } ?: emptyList()
            } else {
                LineLabelPlacement.calculatePlacements(
                    points = line,
                    textWidth = textWidth,
                    spacing = symbolSpacing,
                    maxAngleDeg = maxAngleDeg,
                    tileExtent = canvasSize.toFloat(),
                    fontSize = fontSize,
                )
            }

            placements.forEachIndexed { index, (pos, angle) ->
                /* Upstream's `anchorIsTooClose`: a repeat of the same text within half a
                 * `symbol-spacing` of an anchor already taken in this tile is dropped before it ever
                 * reaches collision detection, so one road does not carry its name twice over. */
                if (!centerOnly && compareText != null &&
                    anchorIsTooClose(compareText, plainText, symbolSpacing / 2f, pos)
                ) return@forEachIndexed
                val x = pos.first + dx
                val y = pos.second + dy
                val displayAngle = if (keepUpright) makeTextUpright(angle) else angle

                val normalizedPoint =
                    tileCoordToNormalized(tileX, tileY, x.toDouble(), y.toDouble(), tileZ, canvasSize)

                val lineTextAngle = displayAngle + textRotateDeg
                val labelPlacement = labelPlacementOf(
                    text = plainText,
                    center = ObbPoint(x, y),
                    width = textWidth,
                    height = textHeight,
                    padding = textPadding,
                    angle = lineTextAngle,
                    layerIndex = layerIndex,
                    layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
                    overlapMode = overlapMode,
                    ignorePlacement = ignorePlacement,
                )
                if (avoidEdges && crossesTileEdge(labelPlacement.bounds, canvasSize)) return@forEachIndexed

                // Create a deterministic ID based on a tile, coordinates and indices
                val coordHash = "${x.toInt()}_${y.toInt()}_${displayAngle.toInt()}"
                out += Symbol.Text(
                    id = "L${tileX}_${tileY}_${id}_${indexLine}_${index}_$coordHash",
                    global = Point(normalizedPoint.x, normalizedPoint.y),
                    placement = CompoundLabelPlacement(labelPlacement, labelPlacement),
                    value = art,
                    viewportAligned = textViewportAligned,
                )
            }
        }

        return out
    }

    /**
     * Upstream's `anchorIsTooClose` (`symbol/symbol_layout.ts`): whether [text] already has an
     * anchor within [repeatDistance] of [anchor] in this tile. Records [anchor] when it does not,
     * so the caller only has to ask.
     *
     * The map is bucket-scoped upstream -- one per tile per style layer -- which is where
     * `SymbolsProducer` keeps it.
     */
    private fun anchorIsTooClose(
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>,
        text: String,
        repeatDistance: Float,
        anchor: Pair<Float, Float>,
    ): Boolean {
        val otherAnchors = compareText.getOrPut(text) { mutableListOf() }
        for (k in otherAnchors.indices.reversed()) {
            if (distance(anchor, otherAnchors[k]) < repeatDistance) return true
        }
        otherAnchors.add(anchor)
        return false
    }

    private fun lineLengthOf(line: List<Pair<Float, Float>>): Float =
        line.zipWithNext { a, b -> distance(a, b) }.sum()

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        sqrt((a.first - b.first).pow(2) + (a.second - b.second).pow(2))

    /**
     * A label on a point feature.
     *
     * The anchor names the side of the label's box that lands on the point, so `top` puts the box
     * *below* it. The signs used to be the other way round, mirroring every non-centre label.
     */
    private fun producePointText(
        placement: SymbolPlacement,
        anchor: TextAnchor,
        dx: Float,
        dy: Float,
        tileX: Int,
        tileY: Int,
        tileZ: Double,
        canvasSize: Int,
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        art: LabelArt,
        density: Density,
        id: String,
        layerIndex: Int
    ): Symbol? {
        val textWidth = art.width
        val textHeight = art.height
        val anchorOffset = anchorCenterOffset(anchor, textWidth, textHeight)
        val textPosition = ObbPoint(
            placement.position.x + dx + anchorOffset.x,
            placement.position.y + dy + anchorOffset.y,
        )

        val normalizedPoint = tileCoordToNormalized(
            tileX = tileX,
            tileY = tileY,
            pixelX = textPosition.x.toDouble(),
            pixelY = textPosition.y.toDouble(),
            tileZ = tileZ,
            tileSize = canvasSize
        )

        val textPadding = (layout.textPadding.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.TEXT_ROTATE.toFloat()
        val pointTextAngle = placement.angle + textRotateDeg

        val labelPlacement = labelPlacementOf(
            text = art.text,
            center = textPosition,
            width = textWidth,
            height = textHeight,
            padding = textPadding,
            angle = pointTextAngle,
            layerIndex = layerIndex,
            layout = layout,
            featureProperties = featureProperties,
            actualZoom = actualZoom,
            overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
            ignorePlacement = layout.textIgnorePlacement?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_IGNORE_PLACEMENT,
        )
        if (avoidsEdges(layout, featureProperties, actualZoom) &&
            crossesTileEdge(labelPlacement.bounds, canvasSize)
        ) {
            return null
        }

        // Add coordinates to ID for uniqueness
        val coordHash = "${textPosition.x.toInt()}_${textPosition.y.toInt()}"
        val textViewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = true
        )
        return Symbol.Text(
            id = "P${tileX}_${tileY}_${id}_$coordHash",
            global = Point(normalizedPoint.x, normalizedPoint.y),
            placement = CompoundLabelPlacement(
                spritePlacement = labelPlacement,
                textPlacement = labelPlacement   // Correct placement for text
            ),
            value = art,
            viewportAligned = textViewportAligned,
        )
    }

    /**
     * Every symbol one feature contributes.
     *
     * A MultiPoint yields one symbol per point, as upstream does -- only the first point used to be
     * placed, so a feature carrying several stops showed one label.
     */
    suspend fun produceSymbol(
        feature: Tile.Feature,
        style: SymbolLayer,
        canvasSize: Int,
        extent: Int,
        tileZ: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        id: String,
        tileX: Int = 0,
        tileY: Int = 0,
        density: Density,
        layerIndex: Int = 0,
        preDecodedLines: List<List<Pair<Float, Float>>>? = null,
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>? = null,
    ): List<Symbol> {
        val layout = style.layout ?: return emptyList()
        style.paint ?: return emptyList()

        val placementMode = placementModeOf(layout, featureProperties, actualZoom)
        val hasSprite = layout.iconImage != null && spriteManager != null
        val hasText = layout.textField != null

        // Calculate the symbol placement
        val pointPlacements: List<SymbolPlacement>
        var lineStrings: List<List<Pair<Float, Float>>>? = null

        if (feature.type == Tile.GeomType.POINT) {
            pointPlacements = calculatePointPlacements(feature, extent, canvasSize)
        } else if (placementMode.isLinePlacement()) {
            /* Both LineString and Polygon carry lines a label can follow: a polygon's rings are what
             * upstream labels when a `line`-placed layer is pointed at an area source.
             *
             * [preDecodedLines] is the same geometry already decoded, and merged with its same-text
             * neighbours by `SymbolsProducer`; upstream merges in `SymbolBucket.populate`, before
             * layout ever sees a feature. */
            val decoded = preDecodedLines ?: when (feature.type) {
                Tile.GeomType.LINESTRING ->
                    geometryDecoders.decodeLine(feature.geometry, extent = extent, canvasSize = canvasSize)

                Tile.GeomType.POLYGON ->
                    geometryDecoders.decodePolygons(feature.geometry, extent = extent, canvasSize = canvasSize)
                        .flatten()

                else -> null
            }

            /* `symbol-placement: line` clips to the tile first, so an anchor never lands in the MVT
             * buffer and gets placed a second time by the neighbour that shares it. `line-center`
             * deliberately does not -- upstream: "No clipping, multiple lines per feature are
             * allowed". */
            lineStrings = decoded
                ?.let {
                    if (placementMode == SYMBOL_PLACEMENT_LINE) {
                        clipLine(it, 0f, 0f, canvasSize.toFloat(), canvasSize.toFloat())
                    } else it
                }
                ?.filter { it.size >= 2 }?.takeIf { it.isNotEmpty() }

            pointPlacements = lineStrings?.firstOrNull()?.firstOrNull()?.let {
                listOf(SymbolPlacement(position = ObbPoint(it.first, it.second), angle = 0f))
            } ?: emptyList()
        } else {
            pointPlacements = emptyList()
        }

        if (pointPlacements.isEmpty()) return emptyList()

        val list = mutableListOf<Symbol>()
        for ((index, placement) in pointPlacements.withIndex()) {
            val pointId = if (pointPlacements.size == 1) id else "${id}_$index"

            if (hasSprite && hasText && feature.type == Tile.GeomType.POINT && lineStrings == null) {
                // Create a SpriteWithText combo symbol for point objects
                val combined = produceSpriteWithText(
                    id = "ST${tileX}_${tileY}_${pointId}",
                    placement = placement,
                    style = style,
                    featureProperties = featureProperties,
                    actualZoom = actualZoom,
                    tileZ = tileZ,
                    canvasSize = canvasSize,
                    tileX = tileX,
                    tileY = tileY,
                    density = density,
                    layerIndex = layerIndex
                )
                if (combined != null) {
                    list += combined
                    continue
                }
            }

            if (hasSprite) {
                produceSprite(
                    id = "S${tileX}_${tileY}_${pointId}",
                    placement = placement,
                    style = style,
                    featureProperties = featureProperties,
                    actualZoom = actualZoom,
                    tileZ = tileZ,
                    canvasSize = canvasSize,
                    tileX = tileX,
                    tileY = tileY,
                    density = density,
                    layerIndex = layerIndex
                )?.let { list.add(it) }
            }

            if (hasText) {
                list += produceText(
                    id = "T${tileX}_${tileY}_${pointId}",
                    placement = placement,
                    style = style,
                    featureProperties = featureProperties,
                    actualZoom = actualZoom,
                    tileZ = tileZ,
                    lineStrings = lineStrings,
                    canvasSize = canvasSize,
                    tileX = tileX,
                    tileY = tileY,
                    density = density,
                    layerIndex = layerIndex,
                    compareText = compareText,
                )
            }
        }

        return list
    }
}

/** Whether a `symbol-placement` value puts symbols along a line rather than on a point. */
private fun String.isLinePlacement(): Boolean =
    this == SYMBOL_PLACEMENT_LINE || this == SYMBOL_PLACEMENT_LINE_CENTER

