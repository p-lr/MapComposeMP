package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.spec.style.ANCHOR_VIEWPORT
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies a `*-translate` / `*-translate-anchor` pair around a draw.
 *
 * Port of `translatePosMatrix` in maplibre-gl-js `src/render/painter.ts`. The offset is specified
 * in *pixels*, which upstream converts to tile units against the current transform; here the draw
 * scope is already the tile bitmap at device density, so the conversion is a plain `* density`.
 *
 * **Divergence:** upstream counter-rotates a `viewport`-anchored translate by `-transform.angle`,
 * so the offset stays screen-aligned while the map is rotated. Tiles here are rasterized once and
 * then rotated with the map, so the bearing is not known at draw time and cannot be cancelled out.
 * A `viewport` anchor therefore applies the same unrotated offset as `map`; it only differs from
 * upstream on a rotated map.
 */
inline fun DrawScope.withTranslate(
    translate: List<Double>?,
    @Suppress("UNUSED_PARAMETER") anchor: String?,
    block: DrawScope.() -> Unit,
) {
    val dx = (translate?.getOrNull(0) ?: 0.0).toFloat() * density
    val dy = (translate?.getOrNull(1) ?: 0.0).toFloat() * density
    if (dx == 0f && dy == 0f) {
        block()
    } else {
        translate(dx, dy) { block() }
    }
}

/**
 * Whether [anchor] is the `viewport` enum value. Kept so painters read the same way as upstream
 * even though [withTranslate] cannot honour the distinction -- see its divergence note.
 */
fun isViewportAnchor(anchor: String?): Boolean = anchor == ANCHOR_VIEWPORT

/**
 * Repeating-sprite brushes for `fill-pattern`, `line-pattern` and `background-pattern`.
 *
 * Upstream packs patterns into an image atlas and repeats them in the shader
 * (`src/render/image_atlas.ts` plus the `*_pattern` shaders). Compose has no atlas, so each pattern
 * becomes its own tiled [ImageShader]; the cut-out bitmaps come from [SpriteManager], which already
 * crops and caches sprite regions for the symbol renderer.
 *
 * Two things the shader does have to be baked into the bitmap here, because a Compose
 * [ShaderBrush] has no local matrix to carry them:
 *
 * - **Scale.** The sprite is a sheet image, so its `pixelRatio` has to be divided out to get a size
 *   in layout pixels, then multiplied by the draw scope's density; and because a tile bitmap is
 *   drawn at `2^(actualZoom - tileZoom)` of its own size, the pattern is pre-scaled by the inverse
 *   so it keeps a constant size on screen. Upstream carries both in `u_scale`.
 * - **Phase.** The shader's `u_pixel_coord_upper/lower` anchor the pattern to a *world* position.
 *   A Compose shader starts at its own draw origin, which is each tile bitmap's corner, so a
 *   pattern whose period does not divide the tile size restarted at every tile boundary. The
 *   bitmap is therefore rolled by the tile's world offset modulo the pattern's period, which puts
 *   the same phase at the same world position and removes the seam.
 *
 * [configure] is called once per tile before anything is painted. One [TileRenderer] serves one
 * tile, so its cache is per-tile too and the phase never has to key the entries.
 */
class PatternBrushCache(maxSize: Int = 64) {
    private val cache = LruCache<String, Brush>(maxSize)

    private var scale: Float = 1f
    private var phaseX: Int = 0
    private var phaseY: Int = 0

    /**
     * Sets the tile the following patterns are drawn into.
     *
     * @param tileX the tile's column, and [tileY] its row, at [tileZoom].
     * @param canvasSize the tile bitmap's edge in pixels, which is also the world distance between
     * two neighbouring tiles' origins at this zoom.
     * @param actualZoom the fractional map zoom, against which the tile is magnified.
     */
    fun configure(
        tileX: Int,
        tileY: Int,
        canvasSize: Int,
        tileZoom: Double,
        actualZoom: Double,
        density: Float,
    ) {
        scale = density * patternZoomScale(tileZoom, actualZoom)
        phaseX = tileX * canvasSize
        phaseY = tileY * canvasSize
        cache.clear()
    }

    /**
     * Returns the tiled brush for [name], or `null` when the style has no sprite sheet, the sheet
     * has no such sprite, or the sprite is an SDF icon -- in which case the caller falls back to
     * the layer's colour rather than dropping the feature.
     *
     * SDF sprites are excluded because they encode a distance field to be re-coloured per use, not
     * a tileable image; upstream only ever resolves patterns against non-SDF images.
     */
    fun get(spriteManager: SpriteManager?, name: String?): Brush? {
        if (spriteManager == null || name.isNullOrEmpty()) return null
        cache.get(name)?.let { return it }

        val info = spriteManager.getSpriteInfo(name) ?: return null
        if (info.sdf) return null
        val (sprite, bitmap) = spriteManager.getSprite(name) ?: return null

        val width = (sprite.layoutWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sprite.layoutHeight * scale).roundToInt().coerceAtLeast(1)
        val tileImage = patternTile(bitmap, width, height, phaseX.mod(width), phaseY.mod(height))

        val brush = ShaderBrush(
            ImageShader(tileImage, TileMode.Repeated, TileMode.Repeated)
        )
        cache.put(name, brush)
        return brush
    }
}

/**
 * How much bigger the pattern has to be drawn in tile space to keep its size on screen.
 *
 * A tile rasterized for zoom `z` is drawn at `2^(actualZoom - z)` of its bitmap size, so the
 * pattern is pre-scaled by the inverse. `VisibleTilesResolver` rounds the level up, which keeps the
 * factor in `[1, 2)`.
 */
internal fun patternZoomScale(tileZoom: Double, actualZoom: Double): Float =
    2.0.pow(tileZoom - actualZoom).toFloat().coerceIn(1f, 2f)

/**
 * One period of the pattern, scaled to [width] x [height] and rolled by ([phaseX], [phaseY]).
 *
 * Rolling is what anchors the pattern to the world rather than to the tile: the same world position
 * gets the same part of the sprite whichever tile happens to cover it. The image is drawn four
 * times, once per wrapped quadrant, because a [TileMode.Repeated] shader cannot be given an offset.
 */
internal fun patternTile(
    source: ImageBitmap,
    width: Int,
    height: Int,
    phaseX: Int,
    phaseY: Int,
): ImageBitmap {
    val target = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(target),
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        for (dy in intArrayOf(0, height)) {
            for (dx in intArrayOf(0, width)) {
                drawImage(
                    image = source,
                    dstOffset = IntOffset(dx - phaseX, dy - phaseY),
                    dstSize = IntSize(width, height),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
    return target
}
