package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.RasterLayer

class RasterLayerPainter : BaseLayerPainter<RasterLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: RasterLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: Map<String, Any?>?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // TODO: Implement bitmap layer rendering
        // 1. Load bitmap
        // 2. Apply transformations
        // 3. Draw with transparency
    }
} 