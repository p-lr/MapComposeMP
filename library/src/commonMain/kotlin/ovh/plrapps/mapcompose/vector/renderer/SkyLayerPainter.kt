package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.SkyLayer

class SkyLayerPainter : BaseLayerPainter<SkyLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: SkyLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // Not implemented. `sky` paints the atmosphere above the horizon, which only exists on a
        // pitched camera (upstream `src/render/draw_sky.ts`). Out of scope for the same reason as
        // `fill-extrusion`.
    }
} 