package ovh.plrapps.mapcompose.vector.data.glyphs

/**
 * One glyph of a font stack, as a `glyphs.pbf` range serves it.
 *
 * The bitmap is a *signed distance field*, not a picture of the glyph: each byte says how far that
 * sample is from the glyph's outline, which is what lets one 24 px rendering be drawn at any size
 * with a crisp edge and an arbitrary halo. See
 * [ovh.plrapps.mapcompose.vector.renderer.utils.sdfPixel], which is the same shader the SDF *icons*
 * go through.
 *
 * All metrics are in the font's own units, where one em is [ONE_EM]. [width] and [height] describe
 * the glyph's ink; [bitmap] is [GLYPH_BORDER] samples larger on every side, so the distance field
 * still has room to fall off outside the ink.
 */
class Glyph(
    val id: Int,
    val bitmap: ByteArray?,
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val advance: Int,
) {
    /** The bitmap's own width, border included. */
    val bitmapWidth: Int get() = width + 2 * GLYPH_BORDER

    /** The bitmap's own height, border included. */
    val bitmapHeight: Int get() = height + 2 * GLYPH_BORDER

    /** Whether the glyph has ink. A space has metrics but no bitmap. */
    val hasBitmap: Boolean get() = bitmap != null && width > 0 && height > 0

    /** The distance-field sample at ([x], [y]) of [bitmap], as `0..1`. Zero outside the bitmap. */
    fun distanceAt(x: Int, y: Int): Float {
        val data = bitmap ?: return 0f
        if (x < 0 || y < 0 || x >= bitmapWidth || y >= bitmapHeight) return 0f
        val index = y * bitmapWidth + x
        if (index >= data.size) return 0f
        return (data[index].toInt() and 0xFF) / 255f
    }
}

/** One em in glyph units. Upstream's `ONE_EM`; every SDF range is rendered at this size. */
const val ONE_EM = 24

/** Samples of distance field carried outside the glyph's ink on each side. Upstream's `border`. */
const val GLYPH_BORDER = 3

/** The glyphs of one font stack over one 256-codepoint range. */
class FontStackGlyphs(
    val fontStack: String,
    val range: String,
    val glyphs: Map<Int, Glyph>,
)
