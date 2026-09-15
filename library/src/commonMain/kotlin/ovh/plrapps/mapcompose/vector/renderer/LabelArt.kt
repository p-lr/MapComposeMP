package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import ovh.plrapps.mapcompose.vector.data.glyphs.RenderedGlyph
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
 *
 * A label is rasterized once, by the layout pass, at the largest size the style asks for while its
 * bucket is on screen (`text-size` at the bucket's zoom + 1). [draw] then takes the `scale` for the
 * zoom actually being drawn, which is never greater than 1 -- see
 * [ovh.plrapps.mapcompose.vector.symbol.getSizeData] -- and is recomputed every frame, as upstream's
 * size uniforms are. Upstream shades glyphs in the fragment shader at the final screen resolution;
 * this port resamples, which is the price of laying a label out once rather than once per viewport
 * update.
 *
 * Abstract rather than sealed, and deliberately: [Glyphs] and [Measured] are the only two ways a
 * label is produced, but nothing matches on the type exhaustively, and a test that needs a label
 * with a box and no ink -- placement and the cross-tile index read nothing else -- cannot subclass a
 * sealed class from a test source set.
 */
internal abstract class LabelArt {

    abstract val width: Float
    abstract val height: Float

    /** Draws the label with its box's top-left corner at [topLeft], scaled about that corner. */
    abstract fun draw(scope: DrawScope, topLeft: Offset, alpha: Float = 1f, scale: Float = 1f)

    /** The label's text, which is what cross-tile and repeat-distance de-duplication key on. */
    abstract val text: String

    /**
     * SDF glyphs from the style's glyph server.
     *
     * [quads] is the same label rasterized glyph by glyph, and is non-null only for a label that
     * follows a line: it is what lets the draw pass bend the label around a curve, one glyph at a
     * time, the way upstream's `placeGlyphsAlongLine` does. [rendered] is built either way, because
     * a line label whose projected path is straight enough takes the single-blit path.
     */
    class Glyphs(
        val rendered: RenderedLabel,
        override val text: String,
        val quads: List<RenderedGlyph>? = null,
    ) : LabelArt() {
        override val width: Float get() = rendered.boxWidth
        override val height: Float get() = rendered.boxHeight

        /**
         * Draws one glyph with its own centre at the current origin, scaled about that centre.
         *
         * The caller has already translated and rotated to where this glyph sits on the label's
         * path, which is why the pivot is the origin and not the label's box corner.
         */
        fun drawGlyph(scope: DrawScope, quad: RenderedGlyph, alpha: Float = 1f, scale: Float = 1f) {
            scope.scale(scale, scale, pivot = Offset.Zero) {
                translate(quad.left, quad.top) {
                    drawImage(quad.bitmap, alpha = alpha)
                }
            }
        }

        override fun draw(scope: DrawScope, topLeft: Offset, alpha: Float, scale: Float) {
            /* The bitmap is larger than the box -- ascenders, descenders and the halo reach outside
             * it -- so it is placed by the box corner's position *within* the bitmap. */
            scope.translate(topLeft.x, topLeft.y) {
                scale(scale, scale, pivot = Offset.Zero) {
                    translate(-rendered.boxLeft, -rendered.boxTop) {
                        drawImage(rendered.bitmap, alpha = alpha)
                    }
                }
            }
        }
    }

    /**
     * Compose-measured text, the fallback for a style with no glyph server.
     *
     * Besides losing a `["format", ...]`'s per-section styling and its inline images, it has no
     * vertical setting: Compose cannot stack a run, so a label that falls back here is always
     * horizontal whatever `text-writing-mode` asks for, and contributes no vertical candidate for
     * the placement pass to choose between.
     */
    class Measured(val layout: TextLayoutResult) : LabelArt() {
        override val text: String get() = layout.layoutInput.text.text
        override val width: Float get() = layout.size.width.toFloat()
        override val height: Float get() = layout.size.height.toFloat()

        override fun draw(scope: DrawScope, topLeft: Offset, alpha: Float, scale: Float) {
            scope.translate(topLeft.x, topLeft.y) {
                scale(scale, scale, pivot = Offset.Zero) {
                    drawText(textLayoutResult = layout, topLeft = Offset.Zero, alpha = alpha)
                }
            }
        }
    }
}
