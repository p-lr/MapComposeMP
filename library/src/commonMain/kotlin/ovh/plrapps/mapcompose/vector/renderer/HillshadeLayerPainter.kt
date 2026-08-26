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
        // `src/data/dem_data.ts`).
        //
        // The source-type plumbing this used to be blocked on now exists: `SourceType.RASTER_DEM`
        // names the source, and `RasterLayerPainter` shows how an image source reaches a painter.
        // What is still missing is the DEM decode itself -- an elevation raster is not drawn as
        // colour, so it needs its own per-source decode rather than `byteArrayToImageBitmap`.
    }
} 