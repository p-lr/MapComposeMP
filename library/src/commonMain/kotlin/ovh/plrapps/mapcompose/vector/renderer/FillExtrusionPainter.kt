package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillExtrusionLayer

class FillExtrusionPainter : BaseLayerPainter<FillExtrusionLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: FillExtrusionLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // Not implemented. `fill-extrusion` is 3D: it extrudes footprints between
        // `fill-extrusion-base` and `fill-extrusion-height` and shades them against the style's
        // light (upstream `src/render/draw_fill_extrusion.ts`). Out of scope while the renderer has
        // no camera pitch.
    }
} 