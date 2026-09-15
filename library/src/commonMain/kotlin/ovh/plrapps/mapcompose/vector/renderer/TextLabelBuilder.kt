package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.data.SDF
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.data.glyphs.Glyph
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphLayout
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphSet
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphRasterizer
import ovh.plrapps.mapcompose.vector.data.glyphs.PUA_BEGIN
import ovh.plrapps.mapcompose.vector.data.glyphs.PUA_END
import ovh.plrapps.mapcompose.vector.data.glyphs.SectionImage
import ovh.plrapps.mapcompose.vector.data.glyphs.TextSection
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_AUTO
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_LEFT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_RIGHT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_LOWERCASE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_UPPERCASE
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.VerticalAlign
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import ovh.plrapps.mapcompose.vector.utils.LruCache

/**
 * Every `text-*` property a label needs, already evaluated against the feature and the zoom.
 *
 * Grouping them keeps the property reads in one place and out of the placement code, and gives the
 * label cache a key: two features whose resolved style is identical share one rasterization.
 */
internal class ResolvedTextStyle(
    val fontStack: List<String>,
    /** `text-size` in **device** pixels. */
    val fontSize: Float,
    val color: Color,
    val opacity: Float,
    val haloColor: Color,
    /** `text-halo-width` in device pixels. */
    val haloWidth: Float,
    /** `text-halo-blur` in device pixels. */
    val haloBlur: Float,
    val letterSpacing: Float,
    val lineHeight: Float,
    val maxWidth: Float,
    val justify: String,
    val transform: String,
    /**
     * `text-writing-mode`, in the style's own preference order -- upstream's `bucket.writingModes`.
     *
     * It is deliberately *not* passed to the shaper: which orientation a label ends up in is decided
     * by the placement pass, which walks this list and keeps the first that fits. It is therefore
     * not part of [cacheKey] either; the resolved orientation is.
     */
    val writingMode: List<String>?,
) {
    /**
     * A key that changes whenever anything visible about the label does.
     *
     * It is keyed on the [Formatted]'s **sections**, not on their concatenated text: a
     * `["format", ...]` carries a per-section font, scale, colour, vertical alignment and inline
     * image, and two labels that read the same but are drawn differently would otherwise share one
     * rasterization.
     *
     * [perGlyph] is part of it because it changes what is *built*, not where the label is drawn --
     * a line label carries per-glyph quads and a point label does not. It stays a boolean: nothing
     * geometric may enter this key, or one label per anchor would be rasterized instead of one per
     * (text, style). [vertical] is there for the same reason: a label eligible for vertical setting
     * is shaped **both** ways, and the two must not share one rasterization.
     */
    fun cacheKey(formatted: Formatted, perGlyph: Boolean, vertical: Boolean): String = buildString {
        append(if (perGlyph) "G|" else "T|")
        append(if (vertical) "V|" else "H|")
        for (section in formatted.sections) {
            append(section.text)
            append(SECTION_FIELD).append(section.image?.name ?: "")
            append(SECTION_FIELD).append(section.scale ?: "")
            append(SECTION_FIELD).append(section.fontStack ?: "")
            append(SECTION_FIELD).append(section.textColor?.toArgb() ?: "")
            append(SECTION_FIELD).append(section.verticalAlign?.value ?: "")
            append(SECTION_END)
        }
        append('|').append(fontStack.joinToString(","))
        append('|').append(fontSize)
        append('|').append(color.toArgb())
        append('|').append(opacity)
        append('|').append(haloColor.toArgb())
        append('|').append(haloWidth)
        append('|').append(haloBlur)
        append('|').append(letterSpacing)
        append('|').append(lineHeight)
        append('|').append(maxWidth)
        append('|').append(justify)
        append('|').append(transform)
    }

    private companion object {
        /** Control characters no `text-field` can contain, so a section's fields cannot run together. */
        val SECTION_FIELD = Char(1)
        val SECTION_END = Char(2)
    }
}

/**
 * Turns a `text-field` into a drawable [LabelArt].
 *
 * Prefers the style's SDF glyph server, and falls back to Compose's [TextMeasurer] when the style
 * declares no `glyphs` URL or the server has no glyph for anything in the label. The fallback is
 * why a style that ships no glyphs still shows labels; its divergences are on [LabelArt.Measured].
 */
internal class TextLabelBuilder(
    private val glyphManager: GlyphManager?,
    private val spriteManager: SpriteManager?,
    private val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    private val cache: LruCache<String, Any>,
    private val mutex: Mutex,
) {

    /**
     * @param perGlyph also rasterize each glyph on its own, which is what a label following a line
     * needs. Only the glyph path can honour it; the Compose fallback ignores it and stays straight.
     * @param vertical build the label's *vertical* setting, one item per line top to bottom.
     * Returns null when this text cannot be set vertically at all, so a caller asking for the second
     * orientation of a label simply gets none -- upstream's
     * `allowsVerticalWritingMode(unformattedText)` guard on `addVerticalShapingForPointLabelIfNeeded`.
     */
    suspend fun build(
        formatted: Formatted,
        style: ResolvedTextStyle,
        density: Density,
        perGlyph: Boolean = false,
        vertical: Boolean = false,
    ): LabelArt? {
        val shapingText = shapingTextOf(formatted)
        if (shapingText.isBlank() || shapingText.length > MAX_LABEL_LENGTH) return null
        if (vertical && !GlyphLayout.allowsVerticalWritingMode(shapingText)) return null

        val key = style.cacheKey(formatted, perGlyph, vertical)
        mutex.withLock { cache.get(key) as? LabelArt }?.let { return it }

        val plainText = formatted.toString()
        val art = renderWithGlyphs(formatted, style, shapingText, density, perGlyph, vertical)
        /* The fallback measures plain text, so it has nothing to say about a field that is only an
         * image -- better no label at all than an empty box where the icon should be. */
            /* The fallback has no vertical setting -- it measures one run through Compose, which
             * cannot stack -- so a vertical variant it produced would be the horizontal box under
             * another name and would win placements it has no business winning. */
            ?: plainText.takeUnless { it.isBlank() || vertical }
                ?.let { measureWithCompose(it, style, density) }
        if (art != null) mutex.withLock { cache.put(key, art) }
        return art
    }

    /**
     * The label's text as the shaper sees it, upstream's `TaggedString.text`.
     *
     * An image section contributes one private-use character rather than nothing at all
     * (`src/symbol/tagged_string.ts`), which is what distinguishes two labels differing only by an
     * inline icon -- and what keeps a field made of an image alone from reading as blank.
     */
    private fun shapingTextOf(formatted: Formatted): String = buildString {
        var imageCodePoint = PUA_BEGIN
        for (section in formatted.sections) {
            val image = section.image
            if (image == null) {
                append(section.text)
                continue
            }
            // Upstream warns and skips both of these.
            if (image.name.isEmpty() || imageCodePoint > PUA_END) continue
            append(Char(imageCodePoint))
            imageCodePoint++
        }
    }

    /**
     * Shapes and rasterizes the label from the style's glyph ranges.
     *
     * Returns `null` when there is nothing this path can draw -- no glyph server and no resolvable
     * inline image, or a server that produced nothing for this text: a font it does not have, or a
     * script outside the ranges it publishes. Either way the caller falls back rather than dropping
     * the label.
     */
    private suspend fun renderWithGlyphs(
        formatted: Formatted,
        style: ResolvedTextStyle,
        shapingText: String,
        density: Density,
        perGlyph: Boolean,
        vertical: Boolean,
    ): LabelArt? {
        val sections = sectionsOf(formatted, style, density)
        if (sections.isEmpty()) return null
        val hasImages = sections.any { it.image != null }

        val manager = glyphManager?.takeIf { it.isConfigured }
        if (manager == null && !hasImages) return null

        /* Upstream's `SymbolBucket.populate` collects glyph dependencies per font stack across
         * *every* section -- `stacks[sectionFont] = stacks[sectionFont] || {}`, then one
         * `calculateGlyphDependencies` per section -- and both halves of that matter here. One
         * `["format", ...]` section may override the font stack, so every stack the label uses has
         * to be fetched and not just the layer's own; and two sections sharing a stack may still
         * need different ranges, so the text they contribute has to be unioned before the fetch.
         * Asking for the first section's text alone left a later section's script unrequested -- a
         * glyph the server has, never asked for, which reads exactly like a font missing it:
         * `GlyphLayout` drops an unresolved codepoint without even an advance. */
        val textByStack = LinkedHashMap<String, Pair<List<String>, StringBuilder>>()
        for (section in sections) {
            if (section.image != null) continue
            val stack = section.fontStack ?: style.fontStack
            val stackKey = stack.joinToString(",")
            textByStack.getOrPut(stackKey) { stack to StringBuilder() }.second.append(section.text)
        }
        val glyphsByStack = mutableMapOf<String, GlyphSet>()
        if (manager != null) {
            for ((stackKey, dependency) in textByStack) {
                val (stack, text) = dependency
                glyphsByStack[stackKey] = manager.glyphsFor(stack, text.toString())
            }
        }
        if (!hasImages && glyphsByStack.values.all { it.isEmpty }) return null

        /* Only a style whose `font-faces` actually drew something here asks the shaper to segment:
         * with nothing to find, a grapheme walk produces the very items a codepoint walk does, at
         * the cost of one segmentation per label. */
        val clusters: ((List<String>, String) -> Glyph?)? =
            if (glyphsByStack.values.any { it.byGrapheme.isNotEmpty() }) {
                { stack, cluster -> glyphsByStack[stack.joinToString(",")]?.byGrapheme?.get(cluster) }
            } else {
                null
            }

        val shaped = GlyphLayout.shape(
            sections = sections,
            glyphs = { stack, code -> glyphsByStack[stack.joinToString(",")]?.byCodePoint?.get(code) },
            defaultFontStack = style.fontStack,
            clusters = clusters,
            fontSize = style.fontSize,
            letterSpacing = style.letterSpacing,
            lineHeight = style.lineHeight,
            maxWidth = style.maxWidth,
            justify = style.justify,
            vertical = vertical,
            // Already applied per section above, so the shaper must not apply it twice.
            transform = StyleSpecDefaults.TEXT_TRANSFORM,
        )
        if (shaped.isEmpty) return null

        val rendered = GlyphRasterizer.render(
            label = shaped,
            fillColor = style.color,
            haloColor = style.haloColor,
            haloWidth = style.haloWidth,
            haloBlur = style.haloBlur,
            opacity = style.opacity,
        ) ?: return null

        /* A vertical or wrapped label has no per-glyph placement along a line: the walk is
         * one-dimensional, so only a single horizontal run can follow the path. A vertical variant
         * is only ever asked for by a point label, so in practice this is the wrapped case. */
        val quads = if (perGlyph && !shaped.vertical && shaped.lines.size == 1) {
            GlyphRasterizer.renderGlyphs(
                label = shaped,
                fillColor = style.color,
                haloColor = style.haloColor,
                haloWidth = style.haloWidth,
                haloBlur = style.haloBlur,
                opacity = style.opacity,
            )
        } else null

        return LabelArt.Glyphs(rendered, shapingText, quads)
    }

    /**
     * The `["format", ...]` sections, with every inline image resolved against the sprite sheet.
     *
     * A section whose image is missing from the sheet is dropped entirely, which is upstream's
     * `if (!imagePosition) continue` (`src/symbol/shaping.ts`) -- it contributes no advance either.
     */
    private fun sectionsOf(
        formatted: Formatted,
        style: ResolvedTextStyle,
        density: Density,
    ): List<TextSection> = formatted.sections.mapNotNull { section ->
        val image = section.image
        val verticalAlign = section.verticalAlign ?: VerticalAlign.BOTTOM
        if (image != null) {
            val resolved = imageOf(image.name, style, density) ?: return@mapNotNull null
            /* `font-scale` sizes text and never an image: upstream's `addImageSection` hardcodes
             * `scale: 1`, and the sprite's own size is the whole of it. */
            TextSection(text = "", image = resolved, verticalAlign = verticalAlign)
        } else {
            TextSection(
                text = transformed(section.text, style.transform),
                scale = section.scale?.toFloat() ?: 1f,
                fontStack = section.fontStack?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                color = section.textColor,
                verticalAlign = verticalAlign,
            )
        }
    }

    /**
     * One inline image, at the size it is drawn.
     *
     * An SDF entry is recoloured by the **text**'s paint properties, not the icon's: upstream draws
     * a label carrying images with `symbol_text_and_icon`, whose `fill_color` / `halo_color` /
     * `halo_width` / `halo_blur` are the text ones, and whose other branch blits a plain image
     * untouched.
     */
    private fun imageOf(name: String, style: ResolvedTextStyle, density: Density): SectionImage? {
        if (name.isEmpty()) return null
        val manager = spriteManager ?: return null
        val info = manager.getSpriteInfo(name) ?: return null
        val sdf = if (info.sdf) {
            SDF(
                fillColor = style.color,
                haloColor = style.haloColor,
                haloWidth = style.haloWidth,
                haloBlur = style.haloBlur,
                /* Drawn size over sheet size, as the icon path computes it: the halo is given in
                 * layout pixels and the distance field measured in sheet ones. */
                fontScale = density.density / info.pixelRatio,
            )
        } else {
            null
        }
        val (_, bitmap) = manager.getSprite(name, sdf = sdf) ?: return null
        return SectionImage(
            width = info.layoutWidth * density.density,
            height = info.layoutHeight * density.density,
            payload = bitmap,
        )
    }

    /**
     * The no-glyph-server fallback.
     *
     * `text-size` is converted to `sp` against the same [Density] the measure uses, which cancels
     * the user's font-scale back out: `text-size` is a length in pixels, not a UI font size, and
     * scaling it with the accessibility setting would resize the map's labels.
     *
     * It measures one run in one style, so a `["format", ...]`'s per-section font, scale and colour
     * are lost here, and so are its inline images. Upstream has no fallback at all.
     */
    private fun measureWithCompose(
        text: String,
        style: ResolvedTextStyle,
        density: Density,
    ): LabelArt? {
        val measurer = textMeasurerState.value ?: return null

        val textStyle = TextStyle(
            color = style.color.withOpacity(style.opacity),
            fontSize = with(density) { style.fontSize.toSp() },
            letterSpacing = with(density) { (style.letterSpacing * style.fontSize).toSp() },
            textAlign = when (style.justify) {
                TEXT_JUSTIFY_LEFT -> TextAlign.Start
                TEXT_JUSTIFY_RIGHT -> TextAlign.End
                else -> TextAlign.Center
            },
            /* A blur behind the glyphs, not a dilated outline: Compose cannot stroke text, so this
             * is the closest the fallback gets to `text-halo-width`. The glyph path above does it
             * properly. The radius is the halo's own width plus its blur -- doubling it, as this
             * used to, made a fallback label's halo twice what the style asked for. */
            shadow = if (style.haloWidth > 0f) {
                Shadow(
                    color = style.haloColor,
                    offset = Offset.Zero,
                    blurRadius = style.haloWidth + style.haloBlur,
                )
            } else {
                null
            },
        )

        val maxWidthPx = style.maxWidth * style.fontSize
        val layout: TextLayoutResult = measurer.measure(
            text = AnnotatedString(transformed(text, style.transform)),
            density = density,
            style = textStyle,
            maxLines = MAX_LABEL_LINES,
            constraints = if (maxWidthPx > 0f && maxWidthPx.isFinite()) {
                Constraints(maxWidth = maxWidthPx.toInt())
            } else {
                Constraints()
            },
            softWrap = true,
        )
        return LabelArt.Measured(layout)
    }

    private fun transformed(text: String, transform: String): String = when (transform) {
        TEXT_TRANSFORM_UPPERCASE -> text.uppercase()
        TEXT_TRANSFORM_LOWERCASE -> text.lowercase()
        else -> text
    }

    companion object {
        /** Longer than any label a style means to draw; a runaway `text-field` is dropped. */
        const val MAX_LABEL_LENGTH = 256

        /** The Compose fallback cannot wrap by ems the way the shaper does, so it is capped. */
        const val MAX_LABEL_LINES = 5

        /**
         * Resolves `text-justify: auto` against the anchor, as upstream's `getAnchorJustification`
         * does: a label anchored to its right edge reads right-aligned.
         */
        fun resolveJustify(justify: String, anchor: String): String = when {
            justify != TEXT_JUSTIFY_AUTO -> justify
            anchor == TextAnchor.Right.value ||
                anchor == TextAnchor.TopRight.value ||
                anchor == TextAnchor.BottomRight.value -> TEXT_JUSTIFY_RIGHT

            anchor == TextAnchor.Left.value ||
                anchor == TextAnchor.TopLeft.value ||
                anchor == TextAnchor.BottomLeft.value -> TEXT_JUSTIFY_LEFT

            else -> TEXT_JUSTIFY_CENTER
        }
    }
}
