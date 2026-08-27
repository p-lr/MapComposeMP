package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_BOTH
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_HEIGHT
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_NONE
import ovh.plrapps.mapcompose.vector.spec.style.ICON_TEXT_FIT_WIDTH
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stretchable ("nine-patch") sprite geometry, ported from maplibre-gl-js `src/render/image_atlas.ts`
 * and the `icon-text-fit` half of `src/symbol/symbol_layout.ts`.
 *
 * A sprite sheet may mark ranges of an icon as stretchable. Resizing such an icon must leave the
 * ranges outside them -- a border, a rounded cap, an arrow head -- at their original size and absorb
 * the whole difference into the marked ranges, which is what makes a speech-bubble icon grow around
 * a label without its corners smearing.
 *
 * Pure geometry, so `commonTest` covers it; only [drawStretchedImage] touches a canvas.
 */

/** One source slice of an icon mapped onto a destination slice, along one axis. */
data class IconStripe(
    val srcFrom: Float,
    val srcTo: Float,
    val dstFrom: Float,
    val dstTo: Float,
) {
    val srcLength: Float get() = srcTo - srcFrom
    val dstLength: Float get() = dstTo - dstFrom
}

/**
 * Cuts one axis of an icon into stripes that map [sourceLength] onto [targetLength].
 *
 * [stretches] are `[from, to]` pairs in the same units as [sourceLength]. With none of them -- or
 * with none that has any length -- the axis scales uniformly, which is what upstream falls back to
 * for an ordinary icon.
 *
 * When the target is smaller than the fixed parts alone there is nothing left to take from the
 * stretchable ranges, so everything is scaled down proportionally instead. Upstream never reaches
 * that case because `icon-text-fit` only ever grows an icon; honouring it here keeps a hand-written
 * size from producing negative stripes.
 */
fun stretchStripes(
    stretches: List<List<Double>>?,
    sourceLength: Float,
    targetLength: Float,
): List<IconStripe> {
    if (sourceLength <= 0f) return emptyList()

    val ranges = stretches.orEmpty()
        .mapNotNull { pair ->
            val from = pair.getOrNull(0)?.toFloat() ?: return@mapNotNull null
            val to = pair.getOrNull(1)?.toFloat() ?: return@mapNotNull null
            val clampedFrom = from.coerceIn(0f, sourceLength)
            val clampedTo = to.coerceIn(0f, sourceLength)
            if (clampedTo > clampedFrom) clampedFrom to clampedTo else null
        }
        .sortedBy { it.first }

    if (ranges.isEmpty()) {
        return listOf(IconStripe(0f, sourceLength, 0f, targetLength))
    }

    // Alternating fixed / stretchable segments across the whole axis.
    val segments = mutableListOf<Triple<Float, Float, Boolean>>()
    var cursor = 0f
    for ((from, to) in ranges) {
        val start = max(from, cursor)
        if (start > cursor) segments += Triple(cursor, start, false)
        if (to > start) segments += Triple(start, to, true)
        cursor = max(cursor, to)
    }
    if (cursor < sourceLength) segments += Triple(cursor, sourceLength, false)

    val fixedTotal = segments.filter { !it.third }.sumOf { (it.second - it.first).toDouble() }.toFloat()
    val stretchTotal = segments.filter { it.third }.sumOf { (it.second - it.first).toDouble() }.toFloat()
    val available = targetLength - fixedTotal

    // Not enough room for the fixed parts: fall back to a uniform scale rather than invert a stripe.
    if (available < 0f || stretchTotal <= 0f) {
        val scale = targetLength / sourceLength
        var dst = 0f
        return segments.map { (from, to, _) ->
            val next = dst + (to - from) * scale
            IconStripe(from, to, dst, next).also { dst = next }
        }
    }

    var dst = 0f
    return segments.map { (from, to, stretchable) ->
        val length = to - from
        val dstLength = if (stretchable) available * (length / stretchTotal) else length
        val next = dst + dstLength
        IconStripe(from, to, dst, next).also { dst = next }
    }
}

/**
 * The size an `icon-text-fit` icon must be drawn at to hold a label of [textWidth] x [textHeight].
 *
 * The sprite's `content` box is the part the label goes in; everything outside it is the icon's own
 * frame and keeps its size, so the icon grows by exactly as much as the padded label exceeds the
 * content box. All lengths are layout pixels, [padding] in `[top, right, bottom, left]` order as
 * `icon-text-fit-padding` gives it.
 *
 * `none` returns the icon's own size unchanged, and so does an axis the mode does not name.
 */
fun iconTextFitSize(
    fit: String,
    sprite: Sprite,
    iconWidth: Float,
    iconHeight: Float,
    textWidth: Float,
    textHeight: Float,
    padding: List<Double>,
): Size {
    if (fit == ICON_TEXT_FIT_NONE) return Size(iconWidth, iconHeight)

    val scaleX = if (sprite.layoutWidth > 0f) iconWidth / sprite.layoutWidth else 1f
    val scaleY = if (sprite.layoutHeight > 0f) iconHeight / sprite.layoutHeight else 1f

    val content = sprite.content
    val contentWidth = if (content != null && content.size >= 4) {
        ((content[2] - content[0]).toFloat() / sprite.pixelRatio) * scaleX
    } else {
        iconWidth
    }
    val contentHeight = if (content != null && content.size >= 4) {
        ((content[3] - content[1]).toFloat() / sprite.pixelRatio) * scaleY
    } else {
        iconHeight
    }

    val padTop = padding.getOrNull(0)?.toFloat() ?: 0f
    val padRight = padding.getOrNull(1)?.toFloat() ?: 0f
    val padBottom = padding.getOrNull(2)?.toFloat() ?: 0f
    val padLeft = padding.getOrNull(3)?.toFloat() ?: 0f

    val frameWidth = iconWidth - contentWidth
    val frameHeight = iconHeight - contentHeight

    val fitsWidth = fit == ICON_TEXT_FIT_WIDTH || fit == ICON_TEXT_FIT_BOTH
    val fitsHeight = fit == ICON_TEXT_FIT_HEIGHT || fit == ICON_TEXT_FIT_BOTH

    val width = if (fitsWidth) max(iconWidth, frameWidth + textWidth + padLeft + padRight) else iconWidth
    val height = if (fitsHeight) max(iconHeight, frameHeight + textHeight + padTop + padBottom) else iconHeight
    return Size(width, height)
}

/**
 * Draws [image] at [dstSize], stretching only the ranges the sheet marked stretchable.
 *
 * With no stretchable range this is one `drawImage`, so it is safe to route every icon through it.
 */
fun DrawScope.drawStretchedImage(
    image: ImageBitmap,
    sprite: Sprite,
    dstOffset: Offset,
    dstSize: Size,
    alpha: Float = 1f,
) {
    val columns = stretchStripes(sprite.stretchX, image.width.toFloat(), dstSize.width)
    val rows = stretchStripes(sprite.stretchY, image.height.toFloat(), dstSize.height)

    if (columns.size == 1 && rows.size == 1) {
        drawImage(
            image = image,
            dstOffset = IntOffset(dstOffset.x.roundToInt(), dstOffset.y.roundToInt()),
            dstSize = IntSize(dstSize.width.roundToInt(), dstSize.height.roundToInt()),
            alpha = alpha,
        )
        return
    }

    for (row in rows) {
        for (column in columns) {
            if (column.srcLength <= 0f || row.srcLength <= 0f) continue
            drawImage(
                image = image,
                srcOffset = IntOffset(column.srcFrom.roundToInt(), row.srcFrom.roundToInt()),
                srcSize = IntSize(column.srcLength.roundToInt(), row.srcLength.roundToInt()),
                dstOffset = IntOffset(
                    (dstOffset.x + column.dstFrom).roundToInt(),
                    (dstOffset.y + row.dstFrom).roundToInt(),
                ),
                dstSize = IntSize(column.dstLength.roundToInt(), row.dstLength.roundToInt()),
                alpha = alpha,
            )
        }
    }
}
