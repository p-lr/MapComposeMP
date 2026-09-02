package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import ovh.plrapps.mapcompose.vector.data.glyphs.RenderedLabel

/**
 * A label, ready to draw, however it was produced.
 *
 * There are two ways to get one. When the style declares a `glyphs` URL the label is shaped and
 * rasterized from the font stack's SDF ranges ([Glyphs]), which is what MapLibre does and what makes
 * `text-font`, a real dilated halo, `text-letter-spacing` and `text-writing-mode` mean anything.
 * Without one, it falls back to Compose's own text stack ([Measured]) so a style that ships no
 * glyphs still renders labels -- with the platform's default font and an approximated halo.
 *
 * Both expose the same box, so everything downstream -- anchoring, collision boxes, drawing -- is
 * written once against [width] and [height] rather than against either representation.
 */
sealed class LabelArt {

    abstract val width: Float
    abstract val height: Float

    /** Draws the label with its box's top-left corner at [topLeft]. */
    abstract fun draw(scope: DrawScope, topLeft: Offset)

    /** The label's text, which is what cross-tile and repeat-distance de-duplication key on. */
    abstract val text: String

    /** SDF glyphs from the style's glyph server. */
    class Glyphs(val rendered: RenderedLabel, override val text: String) : LabelArt() {
        override val width: Float get() = rendered.boxWidth
        override val height: Float get() = rendered.boxHeight

        override fun draw(scope: DrawScope, topLeft: Offset) {
            /* The bitmap is larger than the box -- ascenders, descenders and the halo reach outside
             * it -- so it is placed by the box corner's position *within* the bitmap. */
            scope.translate(topLeft.x - rendered.boxLeft, topLeft.y - rendered.boxTop) {
                drawImage(rendered.bitmap)
            }
        }
    }

    /** Compose-measured text, the fallback for a style with no glyph server. */
    class Measured(val layout: TextLayoutResult) : LabelArt() {
        override val text: String get() = layout.layoutInput.text.text
        override val width: Float get() = layout.size.width.toFloat()
        override val height: Float get() = layout.size.height.toFloat()

        override fun draw(scope: DrawScope, topLeft: Offset) {
            scope.drawText(textLayoutResult = layout, topLeft = topLeft)
        }
    }
}
