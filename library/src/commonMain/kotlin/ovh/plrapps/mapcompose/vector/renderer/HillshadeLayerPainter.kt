package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.HillshadeLayer

class HillshadeLayerPainter : BaseLayerPainter<HillshadeLayer>() {
    override suspend fun paint(
        canvas: DrawScope,
        feature: Tile.Feature,
        style: HillshadeLayer,
        canvasSize: Int,
        extent: Int,
        zoom: Double,
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // Not implemented. A `hillshade` layer needs a `raster-dem` source: elevation tiles decoded
        // from Mapbox or Terrarium RGB encoding, then per-pixel normals lit by
        // `hillshade-illumination-direction` (upstream `src/render/draw_hillshade.ts` and
        // `src/data/dem_data.ts`). Blocked on the same source-type plumbing as the raster layer.
    }
} 