package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.withTranslate
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsBoolean
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDoubleList
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.utils.LruCache

/**
 * Draws `fill` layers.
 *
 * Follows maplibre-gl-js `src/render/draw_fill.ts`: the fill itself, then -- when `fill-antialias`
 * is on, which is the spec default -- a one-pixel outline whose alpha is
 * `outline_color * (alpha * opacity)`, as in `shaders/fill_outline.fragment.glsl`.
 *
 * **Divergence:** upstream falls the outline colour back to `fill-color` when `fill-outline-color`
 * is unset, because that pass is how it antialiases GPU-rasterized triangles. Compose's `drawPath`
 * is antialiased already, so a same-coloured hairline on top would only fatten every polygon and
 * bleed half a pixel into its neighbour -- it is skipped, and the outline is drawn only when the
 * style actually asks for a distinct `fill-outline-color`. `fill-antialias: false` still suppresses
 * it entirely, as upstream does.
 */
class FillLayerPainter(
    private val pathCache: LruCache<String, Any>? = null,
    private val mutex: Mutex? = null,
    private val spriteManager: SpriteManager? = null,
    private val patternBrushes: PatternBrushCache = PatternBrushCache(),
) : BaseLayerPainter<FillLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: FillLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        if (feature.type != Tile.GeomType.POLYGON) return

        val paint = style.paint ?: return

        val path: Path? = if (featureKey != null && pathCache != null && mutex != null) {
            mutex.withLock {
                pathCache.get(featureKey) as? Path
            } ?: createPath(feature, canvasSize, extent)?.also {
                mutex.withLock {
                    pathCache.put(featureKey, it)
                }
            }
        } else {
            createPath(feature, canvasSize, extent)
        }

        if (path == null) return

        val fillColor = paint.fillColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_COLOR
        val fillOpacity = paint.fillOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_OPACITY.toFloat()
        val antialias = paint.fillAntialias.processAsBoolean(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_ANTIALIAS
        val translate = paint.fillTranslate.processAsDoubleList(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_TRANSLATE
        val translateAnchor = paint.fillTranslateAnchor?.processAsString(featureProperties, actualZoom)
            ?: StyleSpecDefaults.FILL_TRANSLATE_ANCHOR

        // A pattern that the sprite sheet cannot supply falls back to fill-color: dropping the
        // feature would punch a hole through every layer drawn underneath it.
        val patternName = paint.fillPattern?.processAsString(featureProperties, actualZoom)
        val patternBrush = patternBrushes.get(spriteManager, patternName)

        val outlineColor = paint.fillOutlineColor?.processAsColor(featureProperties, actualZoom)

        canvas.withTranslate(translate, translateAnchor) {
            if (patternBrush != null) {
                drawPath(path = path, brush = patternBrush, alpha = fillOpacity, style = Fill)
            } else {
                drawPath(path = path, color = fillColor.withOpacity(fillOpacity), style = Fill)
            }

            if (antialias && outlineColor != null) {
                drawPath(
                    path = path,
                    color = outlineColor.withOpacity(fillOpacity),
                    style = Stroke(width = density)
                )
            }
        }
    }
}

/**
 * Multiplies a colour's own alpha by a `*-opacity` value, the way the shaders do -- rather than
 * replacing it, which would make a translucent colour opaque at full opacity.
 */
internal fun Color.withOpacity(opacity: Float): Color =
    if (opacity >= 1f) this else copy(alpha = alpha * opacity.coerceAtLeast(0f))
