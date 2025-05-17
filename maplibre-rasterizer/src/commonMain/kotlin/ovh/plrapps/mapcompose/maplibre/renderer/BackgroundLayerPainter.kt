package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.BackgroundLayer

class BackgroundLayerPainter : BaseLayerPainter<BackgroundLayer>() {
    override fun paint(
        canvas: DrawScope,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: BackgroundLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?
    ) {
        val paint = style.paint ?: return

        val backgroundColor = paint.backgroundColor?.process(featureProperties, zoom) ?: Color.White
        val opacity = paint.backgroundOpacity?.process(featureProperties, zoom) ?: 1f

        canvas.drawRect(
            color = backgroundColor.copy(alpha = opacity),
            size = canvas.size
        )
    }
} 