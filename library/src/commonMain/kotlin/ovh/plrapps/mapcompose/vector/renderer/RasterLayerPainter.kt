package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

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
        featureProperties: EvalFeature?,
        actualZoom: Double,
        featureKey: String?
    ) {
        // Not implemented. A `raster` layer needs a raster *source*, which the tile pipeline does
        // not have: `Source.type` is ignored in `getMapLibreConfiguration`, so every source is
        // fetched and pbf-decoded as MVT. Implementing this means modelling source types, fetching
        // image tiles, and applying `raster-opacity` / `-brightness-min` / `-brightness-max` /
        // `-saturation` / `-contrast` / `-hue-rotate` as a colour matrix (see upstream
        // `src/render/draw_raster.ts` and `shaders/raster.fragment.glsl`).
    }
} 