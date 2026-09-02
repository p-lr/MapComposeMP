package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
 * `fill-antialias` is honoured on the fill itself: the path is drawn through `drawIntoCanvas` with
 * an explicit `Paint`, because `DrawScope.drawPath` builds its own paint and is always antialiased.
 * Turning it off gives the hard, aliased edges upstream's un-antialiased triangles have.
 *
 * **Divergence:** upstream falls the outline colour back to `fill-color` when `fill-outline-color`
 * is unset, because that pass is how it antialiases GPU-rasterized triangles. Skia's coverage
 * antialiasing already does that here, so a same-coloured hairline on top would only fatten every
 * polygon and bleed half a pixel into its neighbour -- it is skipped, and the outline is drawn only
 * when the style actually asks for a distinct `fill-outline-color`.
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
            drawIntoCanvas { canvasHandle ->
                val fill = Paint().apply {
                    isAntiAlias = antialias
                    this.style = PaintingStyle.Fill
                }
                if (patternBrush != null) {
                    patternBrush.applyTo(size, fill, fillOpacity)
                } else {
                    fill.color = fillColor.withOpacity(fillOpacity)
                }
                canvasHandle.drawPath(path, fill)

                if (antialias && outlineColor != null) {
                    canvasHandle.drawPath(
                        path,
                        Paint().apply {
                            isAntiAlias = true
                            this.style = PaintingStyle.Stroke
                            strokeWidth = density
                            color = outlineColor.withOpacity(fillOpacity)
                        },
                    )
                }
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
