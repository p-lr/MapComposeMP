package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.spec.style.ANCHOR_VIEWPORT
import ovh.plrapps.mapcompose.vector.utils.LruCache

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
 * Cached because a pattern brush is rebuilt for every feature of every tile otherwise.
 */
class PatternBrushCache(maxSize: Int = 64) {
    private val cache = LruCache<String, Brush>(maxSize)

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

        if (spriteManager.getSpriteInfo(name)?.sdf != false) return null
        val (_, bitmap) = spriteManager.getSprite(name) ?: return null
        val brush = ShaderBrush(
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
        )
        cache.put(name, brush)
        return brush
    }
}
