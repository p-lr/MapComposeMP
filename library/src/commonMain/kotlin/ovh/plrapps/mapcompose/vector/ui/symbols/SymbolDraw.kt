package ovh.plrapps.mapcompose.vector.ui.symbols

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.renderer.utils.drawStretchedImage
import ovh.plrapps.mapcompose.vector.symbol.SymbolInstance
import ovh.plrapps.mapcompose.vector.symbol.spriteWithTextBounds

/**
 * Drawing a laid-out symbol at the size and opacity the placement pass decided on.
 *
 * Layout produced the symbol at its bucket's size; [iconScale] and [textScale] are
 * `evaluateSizeForFeature(zoom) / layoutSize` for `icon-size` and `text-size` respectively, and
 * [iconAlpha] / [textAlpha] are its fade states. Everything here is a pure function of those, so a
 * fade costs a redraw and not a re-placement.
 */
internal fun SymbolInstance.drawScaled(
    drawScope: DrawScope,
    iconAlpha: Float = 1f,
    textAlpha: Float = 1f,
    iconScale: Float = 1f,
    textScale: Float = 1f,
) {
    val s = scaledSize(iconScale, textScale)
    val symbolCenter = Offset(s.width / 2f, s.height / 2f)
    with(drawScope) {
        when (this@drawScaled) {
            is SymbolInstance.Sprite -> {
                if (iconAlpha <= 0f) return
                rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                    drawStretchedImage(
                        image = value,
                        sprite = spriteMeta,
                        dstOffset = Offset.Zero,
                        dstSize = Size(drawSize.width * iconScale, drawSize.height * iconScale),
                        alpha = opacity * iconAlpha,
                    )
                }
            }

            is SymbolInstance.Text -> {
                if (textAlpha <= 0f) return
                // For text, use the corner from textPlacement (if available) or spritePlacement
                val textAngle = placement.textPlacement?.angle ?: placement.spritePlacement.angle
                rotate(textAngle, pivot = symbolCenter) {
                    val centerX = (s.width - value.width * textScale) / 2f
                    val centerY = (s.height - value.height * textScale) / 2f
                    value.draw(this, Offset(centerX, centerY), alpha = textAlpha, scale = textScale)
                }
            }

            is SymbolInstance.SpriteWithText -> {
                rotate(placement.spritePlacement.angle, pivot = symbolCenter) {
                    /* The drawn box is the union of the icon's and the label's, so each is
                     * placed by its own centre within it. Where the label sits -- below the
                     * icon, beside it, or on it -- is entirely [textOffset]'s business. */
                    val bounds = scaledBounds(iconScale, textScale)
                    val iconCenter = -bounds.topLeft
                    val scaledSpriteWidth = spriteSize.width * iconScale
                    val scaledSpriteHeight = spriteSize.height * iconScale
                    if (iconAlpha > 0f) {
                        drawStretchedImage(
                            image = sprite,
                            sprite = spriteMeta,
                            dstOffset = Offset(
                                iconCenter.x - scaledSpriteWidth / 2f,
                                iconCenter.y - scaledSpriteHeight / 2f,
                            ),
                            dstSize = Size(scaledSpriteWidth, scaledSpriteHeight),
                            alpha = iconOpacity * iconAlpha,
                        )
                    }
                    if (textAlpha > 0f) {
                        text.draw(
                            this,
                            Offset(
                                iconCenter.x + textOffset.x * textScale - textSize.width * textScale / 2f,
                                iconCenter.y + textOffset.y * textScale - textSize.height * textScale / 2f,
                            ),
                            alpha = textAlpha,
                            scale = textScale,
                        )
                    }
                }
            }
        }
    }
}

/** The box a [SymbolInstance.SpriteWithText] occupies once icon and label are scaled. */
internal fun SymbolInstance.SpriteWithText.scaledBounds(iconScale: Float, textScale: Float): Rect =
    spriteWithTextBounds(
        spriteSize = IntSize((spriteSize.width * iconScale).toInt(), (spriteSize.height * iconScale).toInt()),
        textSize = IntSize((textSize.width * textScale).toInt(), (textSize.height * textScale).toInt()),
        textOffset = textOffset * textScale,
    )

/** The symbol's drawn size at the placement pass's scales. */
internal fun SymbolInstance.scaledSize(iconScale: Float, textScale: Float): Size = when (this) {
    is SymbolInstance.SpriteWithText -> scaledBounds(iconScale, textScale).size
    is SymbolInstance.Sprite -> Size(drawSize.width * iconScale, drawSize.height * iconScale)
    is SymbolInstance.Text -> Size(value.width * textScale, value.height * textScale)
}

/**
 * Where the symbol's geographic anchor sits inside its drawn box, as a fraction of that box.
 *
 * `SymbolComposer` positions a symbol by its box's top-left corner, so this is what turns the
 * projected anchor into that corner.
 */
internal fun SymbolInstance.scaledAlign(iconScale: Float, textScale: Float): Offset = when (this) {
    is SymbolInstance.SpriteWithText -> {
        val bounds = scaledBounds(iconScale, textScale)
        if (bounds.width <= 0f || bounds.height <= 0f) SymbolInstance.IN_CENTER
        else Offset(bounds.left / bounds.width, bounds.top / bounds.height)
    }

    else -> SymbolInstance.IN_CENTER
}
