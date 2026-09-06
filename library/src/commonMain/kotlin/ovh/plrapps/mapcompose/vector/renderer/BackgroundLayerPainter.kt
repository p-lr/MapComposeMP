package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.BackgroundLayer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

/**
 * Draws `background` layers -- one flat fill or pattern behind everything else.
 *
 * Follows maplibre-gl-js `src/render/draw_background.ts`. The layer has no source and no features,
 * so [TileRenderer] hands it a synthetic empty feature and it simply covers the tile.
 */
class BackgroundLayerPainter(
    private val spriteManager: SpriteManager? = null,
    private val patternBrushes: PatternBrushCache = PatternBrushCache(),
) : BaseLayerPainter<BackgroundLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: BackgroundLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        val paint = style.paint

        val color = paint.backgroundColor?.processAsColor(featureProperties, actualZoom)
            ?: StyleSpecDefaults.BACKGROUND_COLOR
        val opacity = paint.backgroundOpacity.processAsFloat(featureProperties, actualZoom)
            ?: StyleSpecDefaults.BACKGROUND_OPACITY.toFloat()

        val patternName = paint.backgroundPattern?.processAsString(featureProperties, actualZoom)
        val patternBrush = patternBrushes.get(spriteManager, patternName)

        if (patternBrush != null) {
            canvas.drawRect(brush = patternBrush, alpha = opacity, size = canvas.size)
        } else {
            canvas.drawRect(color = color.withOpacity(opacity), size = canvas.size)
        }
    }
}
