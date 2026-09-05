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
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphLayout
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphRasterizer
import ovh.plrapps.mapcompose.vector.data.glyphs.TextSection
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_AUTO
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_CENTER
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_LEFT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_JUSTIFY_RIGHT
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_LOWERCASE
import ovh.plrapps.mapcompose.vector.spec.style.TEXT_TRANSFORM_UPPERCASE
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.Formatted
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
    val writingMode: List<String>?,
) {
    /** A key that changes whenever anything visible about the label does. */
    fun cacheKey(text: String): String = buildString {
        append("T|").append(text)
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
        append('|').append(writingMode?.joinToString(",") ?: "-")
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
    private val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    private val cache: LruCache<String, Any>,
    private val mutex: Mutex,
) {

    suspend fun build(
        formatted: Formatted,
        style: ResolvedTextStyle,
        density: Density,
    ): LabelArt? {
        val plainText = formatted.toString()
        if (plainText.isBlank() || plainText.length > MAX_LABEL_LENGTH) return null

        val key = style.cacheKey(plainText)
        mutex.withLock { cache.get(key) as? LabelArt }?.let { return it }

        val art = renderWithGlyphs(formatted, style, plainText) ?: measureWithCompose(plainText, style, density)
        if (art != null) mutex.withLock { cache.put(key, art) }
        return art
    }

    /**
     * Shapes and rasterizes the label from the style's glyph ranges.
     *
     * Returns `null` when there is no glyph server, or when it produced nothing for this text --
     * a font the server does not have, or a script outside the ranges it publishes. Either way the
     * caller falls back rather than dropping the label.
     */
    private suspend fun renderWithGlyphs(
        formatted: Formatted,
        style: ResolvedTextStyle,
        plainText: String,
    ): LabelArt? {
        val manager = glyphManager?.takeIf { it.isConfigured } ?: return null

        /* One `["format", ...]` section may override the font stack, so every stack the label uses
         * has to be fetched, not just the layer's own. */
        val sections = formatted.sections.map { section ->
            TextSection(
                text = transformed(section.text, style.transform),
                scale = section.scale?.toFloat() ?: 1f,
                fontStack = section.fontStack?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                color = section.textColor,
            )
        }

        val glyphsByStack = mutableMapOf<String, Map<Int, ovh.plrapps.mapcompose.vector.data.glyphs.Glyph>>()
        for (section in sections) {
            val stack = section.fontStack ?: style.fontStack
            val stackKey = stack.joinToString(",")
            if (stackKey !in glyphsByStack) {
                glyphsByStack[stackKey] = manager.glyphsFor(stack, section.text)
            }
        }
        if (glyphsByStack.values.all { it.isEmpty() }) return null

        val shaped = GlyphLayout.shape(
            sections = sections,
            glyphs = { stack, code -> glyphsByStack[stack.joinToString(",")]?.get(code) },
            defaultFontStack = style.fontStack,
            fontSize = style.fontSize,
            letterSpacing = style.letterSpacing,
            lineHeight = style.lineHeight,
            maxWidth = style.maxWidth,
            justify = style.justify,
            writingMode = style.writingMode,
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
        return LabelArt.Glyphs(rendered, plainText)
    }

    /**
     * The no-glyph-server fallback.
     *
     * `text-size` is converted to `sp` against the same [Density] the measure uses, which cancels
     * the user's font-scale back out: `text-size` is a length in pixels, not a UI font size, and
     * scaling it with the accessibility setting would resize the map's labels.
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
