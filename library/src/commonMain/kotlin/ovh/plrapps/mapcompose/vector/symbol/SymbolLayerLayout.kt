package ovh.plrapps.mapcompose.vector.symbol

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.renderer.GeometryDecoders
import ovh.plrapps.mapcompose.vector.renderer.LabelArt
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.renderer.ResolvedTextStyle
import ovh.plrapps.mapcompose.vector.renderer.TextLabelBuilder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SDF
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.WRITING_MODE_VERTICAL
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFormatted
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsImageName
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsBoolean
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsStringList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.renderer.utils.PaddingSides
import ovh.plrapps.mapcompose.vector.renderer.utils.clipLine
import ovh.plrapps.mapcompose.vector.renderer.utils.paddingSides
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsNumberArray
import ovh.plrapps.mapcompose.vector.renderer.utils.findPoleOfInaccessibility
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite as SpriteInfo
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolPaint
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.renderer.utils.anchorCenterOffset
import ovh.plrapps.mapcompose.vector.renderer.utils.iconTextFitSize
import ovh.plrapps.mapcompose.vector.renderer.utils.isInsideTile
import ovh.plrapps.mapcompose.vector.renderer.utils.radialOffsetEms
import ovh.plrapps.mapcompose.vector.renderer.utils.variableAnchorOffsetEntries
import ovh.plrapps.mapcompose.vector.renderer.utils.variableAnchorOffsets
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsAnyList
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.Size as ObbSize
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import kotlin.collections.zipWithNext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How far past half a label's layout width its stretch of line is cut, as a multiple of that width.
 *
 * A label is walked in layout pixels and drawn in screen ones, and the two differ by the bucket's
 * projection factor -- `2^(bucketZoom - displayZoom)`, never below a half -- so half the drawn width
 * is at most a whole layout width. One and a half leaves room for that and for `line-offset`.
 */
private const val LINE_STRETCH_FACTOR = 1.5f

internal class SymbolLayerLayout(
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
        spriteManager = spriteManager,
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

    /**
     * How an SDF entry is recoloured, or `null` when the sprite is a plain image.
     *
     * `icon-color` and the `icon-halo-*` properties only mean anything for an SDF entry -- upstream
     * ignores them on a plain image entirely, whose program (`symbol_icon.fragment.glsl`) carries no
     * colour uniform: `fragColor = texture(u_texture, v_tex) * alpha`. This used to hand
     * `icon-color` to [ovh.plrapps.mapcompose.vector.data.SpriteManager.getSprite] as a tint for
     * exactly that case, which turned every multicolour PNG icon in such a layer into a monochrome
     * silhouette. An absent `icon-color` on a genuine SDF entry falls back to the spec default
     * rather than skipping the shading: the sprite has no colour of its own, so leaving it unshaded
     * would draw a raw distance field.
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
     * An `icon-image` with its legacy `{token}`s expanded.
     *
     * Every token, and anywhere in the string: `subProcess` -- what this replaces -- returned early
     * unless the name *started* with `{` and then replaced only the first match, so `"poi-{kind}"`
     * was asked of the sprite sheet verbatim and resolved to nothing. Gated on the property being a
     * literal, which is upstream's rule for both token-bearing properties; see [isLiteral].
     */
    private fun resolveIconTokens(
        name: String,
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
    ): String {
        if (!name.contains('{') || !layout.iconImage.isLiteral()) return name
        return substituteTokens(name, featureProperties?.properties)
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
    /**
     * Where a non-point feature is anchored under `symbol-placement: point`.
     *
     * Upstream's `symbol_layout.ts`, the two branches after the line ones:
     *
     * ```
     * } else if (feature.type === 'Polygon') {
     *     for (const polygon of classifyRings(feature.geometry, 0)) {
     *         // 16 here represents 2 pixels
     *         const poi = findPoleOfInaccessibility(polygon, 16);
     *         addSymbolAtAnchor(subdividedLine, new Anchor(poi.x, poi.y, 0));
     *     }
     * } else if (feature.type === 'LineString') {
     *     // https://github.com/mapbox/mapbox-gl-js/issues/3808
     *     for (const line of feature.geometry) {
     *         addSymbolAtAnchor(subdividedLine, new Anchor(line[0].x, line[0].y, 0));
     *     }
     * }
     * ```
     *
     *
     * An anchor outside the tile is dropped, as [calculatePointPlacements]' are: a polygon crossing
     * a boundary is carried by both tiles, and both would otherwise label it.
     */
    private fun calculateAreaPlacements(
        feature: Tile.Feature,
        extent: Int,
        canvasSize: Int,
        /* Upstream passes 16 for an `EXTENT` of 8192, i.e. a 512th of the tile. The geometry here
         * is already scaled to the tile's canvas, so the same fraction of it says the same thing at
         * any bitmap size. */
        precision: Double = canvasSize / 512.0,
    ): List<SymbolAnchorPlacement> {
        val anchors = when (feature.type) {
            Tile.GeomType.POLYGON ->
                geometryDecoders.decodePolygons(feature.geometry, extent = extent, canvasSize = canvasSize)
                    .mapNotNull { polygon -> findPoleOfInaccessibility(polygon, precision) }

            Tile.GeomType.LINESTRING ->
                geometryDecoders.decodeLine(feature.geometry, extent = extent, canvasSize = canvasSize)
                    .mapNotNull { line -> line.firstOrNull() }

            else -> emptyList()
        }
        return anchors
            .filter { isInsideTile(it.first.toDouble(), it.second.toDouble(), canvasSize) }
            .map { SymbolAnchorPlacement(position = ObbPoint(it.first, it.second), angle = 0f) }
    }

    private fun calculatePointPlacements(
        feature: Tile.Feature,
        extent: Int,
        canvasSize: Int
    ): List<SymbolAnchorPlacement> =
        geometryDecoders.decodePoint(geometry = feature.geometry, extent = extent, canvasSize = canvasSize)
            .filter { isInsideTile(it.x, it.y, canvasSize) }
            .map { SymbolAnchorPlacement(position = ObbPoint(it.x.toFloat(), it.y.toFloat()), angle = 0f) }

    private val regexForSubProcess = "\\{([^}]+)\\}".toRegex()

    /**
     * Whether a property's `{token}`s should be expanded at all.
     *
     * Upstream's `getValueAndResolveTokens` (`style/style_layer/symbol_style_layer.ts`) resolves
     * tokens only for a value the style wrote as a plain constant:
     *
     * ```
     * if (!unevaluated.isDataDriven() && !isExpression(unevaluated.value) && value) {
     *     return resolveTokens(feature.properties, value);
     * }
     * ```
     *
     * So text an expression produced is rendered as it stands -- a name that happens to contain
     * `{foo}` is a name, not a template. This port expanded whatever came out of the expression.
     */
    private fun ExpressionOrValue<*>?.isLiteral(): Boolean = this is ExpressionOrValue.Value

    // region resolved style

    /**
     * Every `text-*` property, evaluated once for a feature.
     *
     * [anchor] only matters for `text-justify: auto`, which upstream resolves against the anchor so
     * that a right-anchored label reads right-aligned.
     *
     * [lineLabel] is upstream's `symbol_layout.ts`:
     * `const maxWidth = layout.get('symbol-placement') === 'point' ? layout.get('text-max-width') *
     * ONE_EM : 0;`. A label that follows a line is never wrapped -- there is no sensible way to lay
     * a second line along the same stretch of road, and the per-glyph draw pass has none either.
     */
    private fun resolvedTextStyle(
        layout: SymbolLayout,
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
        anchor: TextAnchor,
        lineLabel: Boolean,
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
            maxWidth = if (lineLabel) 0f else layout.textMaxWidth.processAsFloat(featureProperties, actualZoom)
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
     * rewrites it too -- but only where the tokens actually appear, and only for a `text-field` the
     * style wrote as a plain string (see [isLiteral]). Text an expression produced is rendered as it
     * stands, so a feature whose `name` contains `{foo}` keeps it; this used to rewrite whatever the
     * expression returned. A literal `text-field` with no braces at all is rendered as written too;
     * it used to be silently replaced by the feature's `name`.
     */
    private fun textFieldOf(
        layout: SymbolLayout,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): Formatted? {
        val formatted = layout.textField.processAsFormatted(featureProperties, actualZoom, availableImages)
            ?: return null
        if (formatted.isEmpty()) return null
        if (!layout.textField.isLiteral()) return formatted
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

    /**
     * A label's horizontal setting, its vertical one where there is one, and the style both were
     * built with.
     */
    private class BuiltLabel(
        val art: LabelArt,
        val vertical: LabelArt?,
        val style: ResolvedTextStyle,
    )

    /**
     * The label's art and the style it was built with, or null when the feature has no text.
     *
     * [lineLabel] says the label follows a line: it forbids wrapping (see [resolvedTextStyle]) and
     * asks the builder for the per-glyph quads the curved draw pass needs.
     *
     * A label a style lists `vertical` for is shaped a **second** time, stacked, and both settings
     * are carried to the placement pass -- upstream's `shapedTextOrientations`, whose horizontal
     * half is always built. `text-writing-mode` is a preference *order*, and which end of it a
     * label ends up at is a collision question, not a shaping one.
     *
     * Only a point label gets the second setting, which is upstream's
     * `addVerticalShapingForPointLabelIfNeeded`. Upstream's other vertical branch -- a line label
     * under `textAlongLine && keepUpright` -- is not ported: it rotates each glyph ninety degrees
     * along the path and verticalizes its punctuation, and there is no glyph rotation here.
     */
    private suspend fun buildLabel(
        layout: SymbolLayout,
        paint: SymbolPaint,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        density: Density,
        anchor: TextAnchor,
        lineLabel: Boolean,
    ): BuiltLabel? {
        val formatted = textFieldOf(layout, featureProperties, actualZoom) ?: return null
        val style = resolvedTextStyle(
            layout, paint, featureProperties, actualZoom, density, anchor, lineLabel = lineLabel
        )
        if (style.opacity <= 0f) return null
        val art = labelBuilder.build(formatted, style, density, perGlyph = lineLabel) ?: return null
        val vertical = if (!lineLabel && style.writingMode?.contains(WRITING_MODE_VERTICAL) == true) {
            labelBuilder.build(formatted, style, density, perGlyph = false, vertical = true)
        } else {
            null
        }
        return BuiltLabel(art, vertical, style)
    }

    /** The style's `text-writing-mode`, or the empty list that means "horizontal only". */
    private fun writingModesOf(style: ResolvedTextStyle, vertical: LabelArt?): List<String> =
        if (vertical == null) emptyList() else style.writingMode.orEmpty()

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
    ): String = placementModeOf(style.layout, featureProperties, actualZoom)

    /**
     * The plain text of `text-field`, which is the key upstream's `mergeLines` stitches lines on.
     *
     * Null when the layer has no label at all -- such a feature takes no part in a merge.
     */
    fun mergeTextOf(
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
    ): String? = textFieldOf(style.layout, featureProperties, actualZoom)
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
        padding: PaddingSides,
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
            left = center.x - width / 2f - padding.left,
            top = center.y - height / 2f - padding.top,
            right = center.x + width / 2f + padding.right,
            bottom = center.y + height / 2f + padding.bottom,
        ),
        /* An asymmetric padding moves the box's centre as well as growing it. The shift is applied
         * in world space rather than in the box's own frame, so for a rotated box it is off by the
         * rotation -- which costs nothing at the spec default, where all four sides are equal and
         * the shift is zero, and upstream's collision box is axis-aligned anyway. */
        obb = OBB(
            ObbPoint(center.x + padding.centerShiftX, center.y + padding.centerShiftY),
            ObbSize(width + padding.width, height + padding.height),
            angle,
        ),
        layerIndex = layerIndex,
        inLayerPriority = sortKeyOf(layout, featureProperties, actualZoom),
        overlapMode = overlapMode,
        ignorePlacement = ignorePlacement,
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
        placement: SymbolAnchorPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int,
        sizes: SymbolSizes,
    ): SymbolInstance? {
        val spriteManager = spriteManager ?: return null
        val paint = style.paint
        val layout = style.layout

        val spriteId =
            layout.iconImage.processAsImageName(featureProperties, actualZoom, availableImages)
                ?.let { resolveIconTokens(it, layout, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId)
            ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_SIZE.toFloat()
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_OPACITY.toFloat()
        if (iconOpacity <= 0f) {
            return null
        }
        val scale = iconScale * density.density

        val sdf = sdfFor(spriteInfo, paint, featureProperties, actualZoom, iconScale, density)

        val spritePair = spriteManager.getSprite(spriteId, sdf)
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

        val iconPadding = paddingSides(
            layout.iconPadding.processAsNumberArray(featureProperties, actualZoom),
            default = StyleSpecDefaults.ICON_PADDING,
            scale = density.density,
        )
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
        return SymbolInstance.Sprite(
            id = id,
            key = iconKey(spriteId),
            global = Point(normalizedPoint.x, normalizedPoint.y),
            tileAnchor = Offset(spritePosition.x, spritePosition.y),
            placement = CompoundLabelPlacement(labelPlacement, null),
            value = sprite,
            spriteMeta = spriteMeta,
            drawSize = size,
            opacity = iconOpacity,
            viewportAligned = iconViewportAligned,
            layoutSize = iconScale,
            featureSizes = getFeatureSizes(
                sizes.iconSizeData, layout.iconSize, featureProperties, sizes.tileZoom,
                StyleSpecDefaults.ICON_SIZE,
            ),
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
     * The icon and the label share the feature's anchor: `text-anchor` / `text-variable-anchor` and
     * `text-offset` alone say where the label goes, which is upstream's model and what a style's
     * offsets are authored against. With `icon-text-fit` the label sits *inside* the icon, which is
     * stretched around it, so every anchor candidate collapses onto the icon's own centre.
     */
    private suspend fun produceSpriteWithText(
        id: String,
        placement: SymbolAnchorPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int,
        sizes: SymbolSizes,
    ): SymbolInstance? {
        val layout = style.layout
        val paint = style.paint
        val spriteManager = spriteManager ?: return null

        val spriteId =
            layout.iconImage.processAsImageName(featureProperties, actualZoom, availableImages)
                ?.let { resolveIconTokens(it, layout, featureProperties) }
                ?: return null
        val spriteInfo = spriteManager.getSpriteInfo(spriteId) ?: return null

        val iconScale: Float = layout.iconSize.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_SIZE.toFloat()
        val iconOpacity = paint.iconOpacity.processAsFloat(featureProperties, actualZoom) ?: StyleSpecDefaults.ICON_OPACITY.toFloat()
        if (iconOpacity <= 0f) return null

        val scale = iconScale * density.density

        val sdf = sdfFor(spriteInfo, paint, featureProperties, actualZoom, iconScale, density)
        val spritePair = spriteManager.getSprite(spriteId, sdf) ?: return null
        val (spriteMeta, sprite) = spritePair

        val anchor = textAnchorOf(layout, featureProperties, actualZoom)
        val built = buildLabel(
            layout, paint, featureProperties, actualZoom, density, anchor, lineLabel = false
        ) ?: return null
        val textArt = built.art
        val textStyle = built.style

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

        val iconPadding = paddingSides(
            layout.iconPadding.processAsNumberArray(featureProperties, actualZoom),
            default = StyleSpecDefaults.ICON_PADDING,
            scale = density.density,
        )
        // `text-padding` really is `number` in the spec, so it is the same on every side.
        val textPadding = PaddingSides.uniform(
            (layout.textPadding.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density
        )

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
         * a position of its own: every anchor candidate collapses onto the icon.
         *
         * Failing that, `text-variable-anchor` names the candidates -- and where it is absent,
         * `text-variable-anchor-offset` names them itself, which is upstream walking
         * `variableAnchorOffset.values` two at a time for its `variableTextAnchor`. That property
         * used to supply an offset and nothing more, so a style declaring it alone got the one
         * ordinary anchor and its label simply disappeared where that anchor collided. */
        val anchors: List<TextAnchor> = when {
            textInsideIcon -> listOf(TextAnchor.Center)
            else -> layout.textVariableAnchor?.processAsStringList(featureProperties, actualZoom)
                ?.takeIf { it.isNotEmpty() }
                ?.map { TextAnchor.fromString(it) }
                ?: variableAnchorOffsetEntries(
                    layout.textVariableAnchorOffset.processAsAnyList(featureProperties, actualZoom)
                ).map { it.first }.distinct().takeIf { it.isNotEmpty() }
                ?: listOf(anchor)
        }

        /* One candidate per anchor, for one *setting* of the label: a stacked label is a different
         * box, so it meets each anchor somewhere else and needs a list of its own. The two lists run
         * over the same anchors in the same order, which is what lets one `variableOffsets` index
         * name the same anchor in either. */
        fun candidatesFor(labelText: String, size: IntSize): List<TextPlacementCandidate> =
            anchors.map { candidateAnchor ->
                /* The label is placed by its box: the anchor names the side of the box that lands on
                 * the point, and `text-offset` / `text-radial-offset` push it away from there. The
                 * icon shares that point and takes no room of its own -- upstream's
                 * `symbol_layout.ts` never adds the icon's size to the text offset, and a style's
                 * `text-offset` is authored to clear the icon it is drawn with. */
                val anchorOffset = anchorCenterOffset(
                    candidateAnchor,
                    size.width.toFloat(),
                    size.height.toFloat(),
                )
                val userOffset = textOffsetPx(
                    layout, featureProperties, actualZoom, candidateAnchor, textStyle.fontSize
                )
                val dx = anchorOffset.x + userOffset.x + textTranslate.x
                val dy = anchorOffset.y + userOffset.y + textTranslate.y
                val cx = spritePosition.x + dx
                val cy = spritePosition.y + dy
                val norm = tileCoordToNormalized(tileX, tileY, cx.toDouble(), cy.toDouble(), tileZ, canvasSize)
                TextPlacementCandidate(
                    labelPlacement = labelPlacementOf(
                        text = labelText,
                        center = ObbPoint(cx, cy),
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
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

        val textCandidates: List<TextPlacementCandidate> = candidatesFor(plainText, textSize)

        /* `icon-text-fit` stretched the icon around the *horizontal* box, so a stacked label would
         * sit in an icon shaped for the other setting. Upstream builds a second, vertical icon quad
         * for that case; this port offers no vertical setting there instead. */
        val verticalArt = built.vertical?.takeUnless { textInsideIcon }
        val verticalTextCandidates = verticalArt?.let {
            candidatesFor(it.text, IntSize(it.width.toInt(), it.height.toInt()))
        }.orEmpty()

        /* The label's own placement is the first candidate's -- the style's `text-anchor`, or the
         * first of its `text-variable-anchor` list. Deriving it here rather than recomputing the
         * offset keeps one formula for where the label sits. */
        val primary = textCandidates.first()
        val primaryOffset = Offset(primary.dx, primary.dy)
        val textLabelPlacement = primary.labelPlacement

        /* SymbolComposer positions a symbol by its box's top-left corner, which is the icon's centre
         * shifted by the box's own origin. */
        val bounds = spriteWithTextBounds(spriteSize, textSize, primaryOffset)
        val centerOffset = Offset(
            x = bounds.left / bounds.width,
            y = bounds.top / bounds.height,
        )

        val viewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = true  // produceSpriteWithText is only called for point geometry
        )

        return SymbolInstance.SpriteWithText(
            id = id,
            key = "${textKey(plainText)}|${iconKey(spriteId)}",
            global = Point(normalizedPoint.x, normalizedPoint.y),
            tileAnchor = Offset(spritePosition.x, spritePosition.y),
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
            textOffset = primaryOffset,
            iconOptional = layout.iconOptional?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.ICON_OPTIONAL,
            textOptional = layout.textOptional?.processAsBoolean(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_OPTIONAL,
            iconOpacity = iconOpacity,
            viewportAligned = viewportAligned,
            textCandidates = textCandidates,
            textInsideIcon = textInsideIcon,
            layoutSize = textStyle.fontSize / density.density,
            featureSizes = getFeatureSizes(
                sizes.textSizeData, layout.textSize, featureProperties, sizes.tileZoom,
                StyleSpecDefaults.TEXT_SIZE,
            ),
            iconLayoutSize = iconScale,
            iconFeatureSizes = getFeatureSizes(
                sizes.iconSizeData, layout.iconSize, featureProperties, sizes.tileZoom,
                StyleSpecDefaults.ICON_SIZE,
            ),
            verticalText = verticalArt,
            verticalTextCandidates = verticalTextCandidates,
            writingModes = writingModesOf(textStyle, verticalArt),
        )
    }

    /** Builds the label symbol of a point feature that has text but no icon. */
    private suspend fun produceText(
        placement: SymbolAnchorPlacement,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int,
        sizes: SymbolSizes,
    ): List<SymbolInstance> {
        val layout = style.layout
        val paint = style.paint

        val anchor = textAnchorOf(layout, featureProperties, actualZoom)
        val built = buildLabel(
            layout, paint, featureProperties, actualZoom, density, anchor, lineLabel = false,
        ) ?: return emptyList()
        val art = built.art
        val textStyle = built.style

        val userOffset = textOffsetPx(layout, featureProperties, actualZoom, anchor, textStyle.fontSize)
        val offset = userOffset + textTranslatePx(paint, featureProperties, actualZoom, density)

        return listOfNotNull(
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
                layerIndex = layerIndex,
                fontSize = textStyle.fontSize,
                sizes = sizes,
                verticalArt = built.vertical,
                writingModes = writingModesOf(textStyle, built.vertical),
            )
        )
    }

    /**
     * Every symbol a line-placed feature contributes, icon and label alike.
     *
     * Upstream's `symbol_layout.ts` walks each line **once** -- `getAnchors` for
     * `symbol-placement: line`, `getCenterAnchor` for `line-center` -- and hands every anchor to
     * `addSymbolAtAnchor`, which places the icon and the label together. This port used to put the
     * icon at the first vertex of the first line at angle 0 and let the label walk the line on its
     * own, so a repeated arrow icon became one arrow pointing nowhere and `line-center` started the
     * icon rather than centring it.
     *
     * The label is shaped once per feature, before the walk, because its width is what sets the
     * spacing enlargement -- upstream shapes before `getAnchors` for the same reason. An icon-only
     * layer walks with a width of 0, which is upstream passing no `shapedText`.
     */
    private suspend fun produceAlongLines(
        lineStrings: List<List<Pair<Float, Float>>>,
        style: SymbolLayer,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        tileZ: Double,
        id: String,
        canvasSize: Int,
        tileX: Int,
        tileY: Int,
        density: Density,
        layerIndex: Int,
        sizes: SymbolSizes,
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>?,
        hasSprite: Boolean,
        hasText: Boolean,
    ): List<SymbolInstance> {
        val layout = style.layout
        val paint = style.paint

        val anchor = textAnchorOf(layout, featureProperties, actualZoom)
        val built = if (hasText) {
            buildLabel(layout, paint, featureProperties, actualZoom, density, anchor, lineLabel = true)
        } else {
            null
        }
        if (built == null && !hasSprite) return emptyList()

        val textOffset = if (built != null) {
            textOffsetPx(layout, featureProperties, actualZoom, anchor, built.style.fontSize) +
                textTranslatePx(paint, featureProperties, actualZoom, density)
        } else {
            Offset.Zero
        }
        val textWidth = built?.art?.width ?: 0f
        val fontSize = built?.style?.fontSize ?: 0f
        val plainText = built?.art?.text

        /* Upstream's `bucket.overscaling`: how many map tiles this canonical tile covers per axis,
         * which is the bucket's `TileRef.span`. The layout space is `layoutTileSize(density, span)`
         * wide, so it is what that size is a multiple of. */
        val overscaling = (canvasSize / layoutTileSize(density.density, span = 1)).coerceAtLeast(1)
        val symbolSpacing = (layout.symbolSpacing.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.SYMBOL_SPACING.toFloat()) * density.density
        val maxAngleDeg = layout.textMaxAngle.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_MAX_ANGLE.toFloat()
        val centerOnly = placementModeOf(layout, featureProperties, actualZoom) ==
            SYMBOL_PLACEMENT_LINE_CENTER

        val out = mutableListOf<SymbolInstance>()

        lineStrings.forEachIndexed lineStrings@{ lineIndex, line ->
            if (line.size < 2) return@lineStrings
            if (lineLengthOf(line) < textWidth) return@lineStrings

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
                    overscaling = overscaling,
                )
            }

            placements.forEachIndexed { index, (position, angle) ->
                /* Upstream's `anchorIsTooClose`: a repeat of the same text within half a
                 * `symbol-spacing` of an anchor already taken in this tile is dropped before it ever
                 * reaches collision detection, so one road does not carry its name twice over. It
                 * gates the whole anchor, icon included, exactly as upstream's own
                 * `if (!shapedText || !anchorIsTooClose(...)) addSymbolAtAnchor(...)` does. */
                if (!centerOnly && compareText != null && plainText != null &&
                    anchorIsTooClose(compareText, plainText, symbolSpacing / 2f, position)
                ) return@forEachIndexed

                if (hasSprite) {
                    produceSprite(
                        id = "S${tileX}_${tileY}_${id}_${lineIndex}_$index",
                        placement = SymbolAnchorPlacement(
                            position = ObbPoint(position.first, position.second),
                            angle = angle,
                        ),
                        style = style,
                        featureProperties = featureProperties,
                        actualZoom = actualZoom,
                        tileZ = tileZ,
                        canvasSize = canvasSize,
                        tileX = tileX,
                        tileY = tileY,
                        density = density,
                        layerIndex = layerIndex,
                        sizes = sizes,
                    )?.let { out.add(it) }
                }

                if (built != null) {
                    produceLineTextAt(
                        id = "L${tileX}_${tileY}_T${tileX}_${tileY}_${id}_${lineIndex}_$index",
                        line = line,
                        position = position,
                        angle = angle,
                        layout = layout,
                        featureProperties = featureProperties,
                        actualZoom = actualZoom,
                        dx = textOffset.x,
                        dy = textOffset.y,
                        tileX = tileX,
                        tileY = tileY,
                        art = built.art,
                        tileZ = tileZ,
                        canvasSize = canvasSize,
                        density = density,
                        layerIndex = layerIndex,
                        fontSize = built.style.fontSize,
                        sizes = sizes,
                    )?.let { out.add(it) }
                }
            }
        }

        return out
    }

    /**
     * One label at one of the anchors [produceAlongLines] walked out.
     *
     * Everything here is per-anchor; what is per-feature -- the shaping, the spacing, the walk --
     * belongs to the caller, which is what lets an icon share the anchor.
     */
    private fun produceLineTextAt(
        id: String,
        line: List<Pair<Float, Float>>,
        position: Pair<Float, Float>,
        angle: Float,
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
        sizes: SymbolSizes,
    ): SymbolInstance? {
        val textRotateDeg = layout.textRotate.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_ROTATE.toFloat()
        val keepUpright = layout.textKeepUpright?.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.TEXT_KEEP_UPRIGHT
        val textViewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = false  // "auto" + line placement = map-aligned
        )
        // `text-padding` really is `number` in the spec, so it is the same on every side.
        val textPadding = PaddingSides.uniform(
            (layout.textPadding.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density
        )
        val plainText = art.text

        val x = position.first + dx
        val y = position.second + dy
        val displayAngle = if (keepUpright) makeTextUpright(angle) else angle

        val normalizedPoint =
            tileCoordToNormalized(tileX, tileY, x.toDouble(), y.toDouble(), tileZ, canvasSize)
        /* The stretch of road this label covers, cut around the anchor *before* the offsets are
         * applied -- the draw pass walks from there and applies them along the path, as upstream's
         * `lineOffsetX` / `lineOffsetY` do. The cut is generous: the label is drawn in screen pixels
         * and walked in layout ones, and the two differ by the bucket's projection factor, which is
         * never below a half. */
        val stretch = SymbolProjection.labelPathOf(
            line = line,
            anchor = Offset(position.first, position.second),
            halfLength = art.width * LINE_STRETCH_FACTOR + abs(dx),
        )
        val globalLine = stretch?.points?.map { point ->
            tileCoordToNormalized(tileX, tileY, point.x.toDouble(), point.y.toDouble(), tileZ, canvasSize)
        }

        val labelPlacement = labelPlacementOf(
            text = plainText,
            center = ObbPoint(x, y),
            width = art.width,
            height = art.height,
            padding = textPadding,
            angle = displayAngle + textRotateDeg,
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

        // Create a deterministic ID based on a tile, coordinates and indices
        val coordHash = "${x.toInt()}_${y.toInt()}_${displayAngle.toInt()}"
        return SymbolInstance.Text(
            id = "${id}_$coordHash",
            key = textKey(plainText),
            global = Point(normalizedPoint.x, normalizedPoint.y),
            tileAnchor = Offset(x, y),
            placement = CompoundLabelPlacement(labelPlacement, labelPlacement),
            value = art,
            viewportAligned = textViewportAligned,
            line = line,
            globalLine = globalLine,
            globalAnchorIndex = stretch?.anchorIndex ?: 0,
            lineOffsetX = dx,
            lineOffsetY = dy,
            keepUpright = keepUpright,
            layoutSize = fontSize / density.density,
            featureSizes = getFeatureSizes(
                sizes.textSizeData, layout.textSize, featureProperties, sizes.tileZoom,
                StyleSpecDefaults.TEXT_SIZE,
            ),
        )
    }

    /**
     * Upstream's `anchorIsTooClose` (`symbol/symbol_layout.ts`): whether [text] already has an
     * anchor within [repeatDistance] of [anchor] in this tile. Records [anchor] when it does not,
     * so the caller only has to ask.
     *
     * The map is bucket-scoped upstream -- one per tile per style layer -- which is where
     * `SymbolBucketBuilder` keeps it.
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
        placement: SymbolAnchorPlacement,
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
        layerIndex: Int,
        fontSize: Float,
        sizes: SymbolSizes,
        verticalArt: LabelArt? = null,
        writingModes: List<String> = emptyList(),
    ): SymbolInstance? {
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

        // `text-padding` really is `number` in the spec, so it is the same on every side.
        val textPadding = PaddingSides.uniform(
            (layout.textPadding.processAsFloat(featureProperties, actualZoom)
                ?: StyleSpecDefaults.TEXT_PADDING.toFloat()) * density.density
        )
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

        /* The stacked setting is a different box, so it takes a different centre: the anchor names
         * the side of the box that lands on the point, and a tall narrow box meets it elsewhere. */
        val verticalSetting = verticalArt?.let { stacked ->
            val stackedAnchorOffset = anchorCenterOffset(anchor, stacked.width, stacked.height)
            val stackedPosition = ObbPoint(
                placement.position.x + dx + stackedAnchorOffset.x,
                placement.position.y + dy + stackedAnchorOffset.y,
            )
            val stackedNormalized = tileCoordToNormalized(
                tileX = tileX,
                tileY = tileY,
                pixelX = stackedPosition.x.toDouble(),
                pixelY = stackedPosition.y.toDouble(),
                tileZ = tileZ,
                tileSize = canvasSize,
            )
            VerticalSetting(
                value = stacked,
                placement = labelPlacementOf(
                    text = stacked.text,
                    center = stackedPosition,
                    width = stacked.width,
                    height = stacked.height,
                    padding = textPadding,
                    angle = pointTextAngle,
                    layerIndex = layerIndex,
                    layout = layout,
                    featureProperties = featureProperties,
                    actualZoom = actualZoom,
                    overlapMode = resolveTextOverlapMode(layout, featureProperties, actualZoom),
                    ignorePlacement = layout.textIgnorePlacement
                        ?.processAsBoolean(featureProperties, actualZoom)
                        ?: StyleSpecDefaults.TEXT_IGNORE_PLACEMENT,
                ),
                global = Point(stackedNormalized.x, stackedNormalized.y),
                tileAnchor = Offset(stackedPosition.x, stackedPosition.y),
            )
        }

        // Add coordinates to ID for uniqueness
        val coordHash = "${textPosition.x.toInt()}_${textPosition.y.toInt()}"
        val textViewportAligned = resolveViewportAligned(
            layout.textRotationAlignment?.processAsString(featureProperties, actualZoom),
            defaultViewportAligned = true
        )
        return SymbolInstance.Text(
            id = "P${tileX}_${tileY}_${id}_$coordHash",
            key = textKey(art.text),
            global = Point(normalizedPoint.x, normalizedPoint.y),
            tileAnchor = Offset(textPosition.x, textPosition.y),
            placement = CompoundLabelPlacement(
                spritePlacement = labelPlacement,
                textPlacement = labelPlacement   // Correct placement for text
            ),
            value = art,
            viewportAligned = textViewportAligned,
            layoutSize = fontSize / density.density,
            featureSizes = getFeatureSizes(
                sizes.textSizeData, layout.textSize, featureProperties, sizes.tileZoom,
                StyleSpecDefaults.TEXT_SIZE,
            ),
            verticalSetting = verticalSetting,
            writingModes = writingModes,
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
        sizes: SymbolSizes,
        preDecodedLines: List<List<Pair<Float, Float>>>? = null,
        compareText: MutableMap<String, MutableList<Pair<Float, Float>>>? = null,
    ): List<SymbolInstance> {
        val layout = style.layout

        val placementMode = placementModeOf(layout, featureProperties, actualZoom)
        val hasSprite = layout.iconImage != null && spriteManager != null
        val hasText = layout.textField != null

        // Calculate the symbol placement
        val pointPlacements: List<SymbolAnchorPlacement>
        var lineStrings: List<List<Pair<Float, Float>>>? = null

        if (feature.type == Tile.GeomType.POINT) {
            pointPlacements = calculatePointPlacements(feature, extent, canvasSize)
        } else if (placementMode.isLinePlacement()) {
            /* Both LineString and Polygon carry lines a label can follow: a polygon's rings are what
             * upstream labels when a `line`-placed layer is pointed at an area source.
             *
             * [preDecodedLines] is the same geometry already decoded, and merged with its same-text
             * neighbours by `SymbolBucketBuilder`; upstream merges in `SymbolBucket.populate`, before
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

            pointPlacements = emptyList()
        } else {
            /* `symbol-placement: point` over a line or an area, which upstream places too: a polygon
             * is labelled at its pole of inaccessibility, a line at the first vertex of each of its
             * parts. Both used to produce nothing at all, so a polygon layer with a `text-field`
             * drew no labels. */
            pointPlacements = calculateAreaPlacements(feature, extent, canvasSize)
        }

        /* A line-placed layer anchors icon *and* label at the same walk of the line, which is
         * upstream's `addSymbolAtAnchor` under one `getAnchors` call. This used to place the icon at
         * the first vertex of the first line, at angle 0, while the label walked the line on its
         * own -- so a repeated arrow became one arrow pointing nowhere, `line-center` put the icon at
         * the start rather than the middle, and an icon and its label could drift apart. */
        if (lineStrings != null) {
            return produceAlongLines(
                lineStrings = lineStrings,
                style = style,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                tileZ = tileZ,
                id = id,
                canvasSize = canvasSize,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex,
                sizes = sizes,
                compareText = compareText,
                hasSprite = hasSprite,
                hasText = hasText,
            )
        }

        if (pointPlacements.isEmpty()) return emptyList()

        val list = mutableListOf<SymbolInstance>()
        for ((index, placement) in pointPlacements.withIndex()) {
            val pointId = if (pointPlacements.size == 1) id else "${id}_$index"

            if (hasSprite && hasText && feature.type == Tile.GeomType.POINT) {
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
                    layerIndex = layerIndex,
                    sizes = sizes,
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
                    layerIndex = layerIndex,
                    sizes = sizes,
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
                    canvasSize = canvasSize,
                    tileX = tileX,
                    tileY = tileY,
                    density = density,
                    layerIndex = layerIndex,
                    sizes = sizes,
                )
            }
        }

        return list
    }
}

/**
 * The cross-tile identity of a label, upstream's `SymbolInstance.key`.
 *
 * `cross_tile_symbol_index.ts` matches a child tile's symbol to its parent's on the text, so two
 * tiles carrying the same road name recognise it as one symbol rather than two.
 */
internal fun textKey(text: String): String = "t:$text"

/** The cross-tile identity of an icon; see [textKey]. */
internal fun iconKey(spriteId: String): String = "i:$spriteId"

/**
 * `icon-overlap`, falling back to the deprecated `icon-allow-overlap` -- upstream's `getOverlapMode`
 * (`style/style_layer/symbol_style_layer.ts`).
 *
 * Top-level rather than a member because the *layer* value, evaluated with no feature, is what
 * [symbolOrderingFor] needs for upstream's `canOverlap`.
 */
internal fun resolveIconOverlapMode(
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

/** See [resolveIconOverlapMode]; `text-overlap` / `text-allow-overlap`. */
internal fun resolveTextOverlapMode(
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

/** Whether a `symbol-placement` value puts symbols along a line rather than on a point. */
private fun String.isLinePlacement(): Boolean =
    this == SYMBOL_PLACEMENT_LINE || this == SYMBOL_PLACEMENT_LINE_CENTER

