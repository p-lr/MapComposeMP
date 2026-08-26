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
        // Not implemented. Upstream accumulates a density field for the whole viewport into an
        // offscreen framebuffer and then maps it through `heatmap-color`
        // (`src/render/draw_heatmap.ts`). Tiles here are rasterized independently, so a per-tile
        // heatmap would seam at every tile edge; this needs a screen-space overlay like
        // `SymbolComposer`, not a per-tile painter.
    }
} 