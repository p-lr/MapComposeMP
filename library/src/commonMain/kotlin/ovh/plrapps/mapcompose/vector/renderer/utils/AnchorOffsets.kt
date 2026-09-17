package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import ovh.plrapps.mapcompose.vector.spec.style.symbol.TextAnchor
import kotlin.math.sqrt

/**
 * Where an anchored box sits relative to its anchor point. Port of maplibre-gl-js
 * `getAnchorAlignment` and `evaluateVariableOffset` in `src/symbol/symbol_layout.ts`.
 *
 * An anchor names the part of the box that lands *on* the point: `top` puts the box's top edge
 * there, so the label hangs below. This used to be implemented with the opposite sign, mirroring
 * every non-centre label through its own anchor point.
 */

/** `horizontalAlign` in [Offset.x] and `verticalAlign` in [Offset.y], both `0`, `0.5` or `1`. */
fun anchorAlignment(anchor: TextAnchor): Offset {
    val horizontal = when (anchor) {
        TextAnchor.Right, TextAnchor.TopRight, TextAnchor.BottomRight -> 1f
        TextAnchor.Left, TextAnchor.TopLeft, TextAnchor.BottomLeft -> 0f
        else -> 0.5f
    }
    val vertical = when (anchor) {
        TextAnchor.Bottom, TextAnchor.BottomRight, TextAnchor.BottomLeft -> 1f
        TextAnchor.Top, TextAnchor.TopRight, TextAnchor.TopLeft -> 0f
        else -> 0.5f
    }
    return Offset(horizontal, vertical)
}

/**
 * The offset from the anchor point to the box's **centre**, for a box of [width] x [height].
 *
 * The placement code works in centres -- a `LabelPlacement` is a centre plus a size -- so this is
 * the form it needs; [anchorAlignment] is the corner form upstream works in.
 */
fun anchorCenterOffset(anchor: TextAnchor, width: Float, height: Float): Offset {
    val alignment = anchorAlignment(anchor)
    return Offset((0.5f - alignment.x) * width, (0.5f - alignment.y) * height)
}

/**
 * `text-radial-offset` resolved into an `[x, y]` offset in ems, following the anchor's direction.
 *
 * Port of upstream's `evaluateVariableOffset` radial branch: a diagonal anchor gets the offset
 * split over both axes by `1/sqrt(2)`, so every anchor sits the same distance from the point.
 *
 * **Divergence:** upstream also shifts the vertical component by a constant baseline correction, to
 * account for its text being positioned from a pen offset rather than from a box. `GlyphLayout`
 * folds that offset into the box it returns (see its `SHAPING_DEFAULT_OFFSET`), so the box is
 * already centred on the ink and the term is omitted here.
 */
fun radialOffsetEms(anchor: TextAnchor, radialOffset: Float): Offset {
    val radial = radialOffset.coerceAtLeast(0f)
    val diagonal = radial / sqrt(2f)
    val x = when (anchor) {
        TextAnchor.TopRight, TextAnchor.BottomRight -> -diagonal
        TextAnchor.TopLeft, TextAnchor.BottomLeft -> diagonal
        TextAnchor.Left -> radial
        TextAnchor.Right -> -radial
        else -> 0f
    }
    val y = when (anchor) {
        TextAnchor.TopRight, TextAnchor.TopLeft -> diagonal
        TextAnchor.BottomRight, TextAnchor.BottomLeft -> -diagonal
        TextAnchor.Top -> radial
        TextAnchor.Bottom -> -radial
        else -> 0f
    }
    return Offset(x, y)
}

/**
 * `text-variable-anchor-offset` read into its anchors **in order**, each with its offset in ems.
 *
 * The spec's `variableAnchorOffsetCollection` is a flat list alternating an anchor name and its own
 * `[x, y]`: `["top", [0, 1], "left", [2, 0]]`. Anything malformed is skipped rather than failing the
 * property, which is what the rest of the style parsing does with a value it cannot use.
 *
 * The order is the property's own, because it is also the order the placement pass tries the
 * anchors in when the property stands alone -- upstream walks `variableAnchorOffset.values` two at
 * a time to derive `variableTextAnchor`. A list rather than a map for the same reason, and because
 * a repeated anchor must not silently replace the offset written before it.
 */
fun variableAnchorOffsetEntries(raw: List<Any?>?): List<Pair<TextAnchor, Offset>> {
    if (raw.isNullOrEmpty()) return emptyList()
    val out = mutableListOf<Pair<TextAnchor, Offset>>()
    var index = 0
    while (index + 1 < raw.size) {
        val name = anchorName(raw[index])
        val offset = numberPair(raw[index + 1])
        index += 2
        if (name == null || offset == null) continue
        out += TextAnchor.fromString(name) to offset
    }
    return out
}

/**
 * The same collection as a lookup. The first entry for an anchor wins, as the first candidate for
 * it is the one the placement pass reaches first.
 */
fun variableAnchorOffsets(raw: List<Any?>?): Map<TextAnchor, Offset> {
    val entries = variableAnchorOffsetEntries(raw)
    if (entries.isEmpty()) return emptyMap()
    val out = mutableMapOf<TextAnchor, Offset>()
    for ((anchor, offset) in entries) out.getOrPut(anchor) { offset }
    return out
}

/**
 * The entries arrive either as parsed JSON (a constant property) or as the engine's own values (an
 * expression), so both shapes are accepted.
 */
private fun anchorName(value: Any?): String? = when (value) {
    is String -> value
    is JsonPrimitive -> value.contentOrNull
    else -> null
}

private fun numberPair(value: Any?): Offset? {
    val items: List<Any?> = when (value) {
        is JsonArray -> value.toList()
        is List<*> -> value
        else -> return null
    }
    if (items.size < 2) return null
    val x = numberOf(items[0]) ?: return null
    val y = numberOf(items[1]) ?: return null
    return Offset(x, y)
}

private fun numberOf(value: Any?): Float? = when (value) {
    is Number -> value.toFloat()
    is JsonPrimitive -> value.doubleOrNull?.toFloat()
    else -> null
}
