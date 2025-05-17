package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.RasterLayer

class RasterLayerPainter : BaseLayerPainter<RasterLayer>() {
    override fun paint(
        canvas: DrawScope,
        collisionDetector: CollisionDetector,
        feature: Tile.Feature,
        style: RasterLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?
    ) {
        // TODO: Implement bitmap layer rendering
        // 1. Load bitmap
        // 2. Apply transformations
        // 3. Draw with transparency
    }
} 