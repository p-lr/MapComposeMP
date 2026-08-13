package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.HeatmapLayer

class HeatmapLayerPainter : BaseLayerPainter<HeatmapLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: HeatmapLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // TODO: Implement heatmap rendering
        // 1. Collect all points with weights
        // 2. Apply Gaussian blur
        // 3. Normalize values
        // 4. Apply color scheme
    }
} 