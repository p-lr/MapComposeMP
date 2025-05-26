package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.*

class TileRenderer(
    configuration: MapLibreConfiguration,
) : BaseRenderer(configuration = configuration) {
    private val painters = mutableMapOf<Layer, BaseLayerPainter<*>>()

    fun render(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: Layer,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
    ) {
        if (!isZoomInRange(styleLayer, zoom)) {
            // println("  missed by zoom")
            return
        }
        when (styleLayer) {
            is SymbolLayer -> {return}
            is BackgroundLayer -> {
                painters
                    .getOrPut(styleLayer) {
                        BackgroundLayerPainter()
                    }.let {
                        it as BackgroundLayerPainter
                    }
                    .paint(
                        canvas = canvas,
                        feature = Tile.Feature(
                            id = -1,
                            type = Tile.GeomType.POINT,
                            geometry = emptyList(),
                            tags = emptyList()
                        ),
                        style = styleLayer,
                        canvasSize = canvasSize,
                        extent = 4096,
                        zoom = zoom,
                        featureProperties = null,
                        actualZoom = actualZoom,
                    )
            }
            is CircleLayer,
            is FillLayer,
            is LineLayer,
            is SymbolLayer,
            is FillExtrusionLayer,
            is HeatmapLayer,
            is RasterLayer,
            is SkyLayer,
            is HillshadeLayer -> {
                if (tile == null || tile.layers.isEmpty()) return
                val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer }
                if (tileLayer == null) {
//                    println("source-layer '${styleLayer.sourceLayer}' not found")
                    return
                }
                val extent = tileLayer.extent ?: 4096
                val painter = painters
                    .getOrPut(styleLayer) {
                        when (styleLayer) {
                            is SymbolLayer,
                            is BackgroundLayer -> throw IllegalStateException("BackgroundLayer cant be here")
                            is CircleLayer -> CircleLayerPainter()
                            is FillExtrusionLayer -> FillExtrusionPainter()
                            is FillLayer -> FillLayerPainter()
                            is HeatmapLayer -> HeatmapLayerPainter()
                            is HillshadeLayer -> HillshadeLayerPainter()
                            is LineLayer -> LineLayerPainter()
                            is RasterLayer -> RasterLayerPainter()
                            is SkyLayer -> SkyLayerPainter()
//                            is SymbolLayer -> SymbolLayerPainter(
//                                textMeasurer = textMeasurer,
//                                spriteManager = configuration.spriteManager
//                            )
                        }
                    }

                for (feature in tileLayer.features) {
                    val isShouldRenderFeature = shouldRenderFeature( feature, tileLayer, styleLayer, zoom)
                    if (!isShouldRenderFeature) continue

                    val featureProperties = extractFeatureProperties(feature, tileLayer)

                    when (styleLayer) {
                        is CircleLayer -> (painter as CircleLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is FillLayer -> (painter as FillLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is LineLayer -> (painter as LineLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is SymbolLayer -> { /*do nothing; render happened in separate place*/}
                        is BackgroundLayer -> throw IllegalStateException("BackgroundLayer cant be here")
                        is FillExtrusionLayer -> (painter as FillExtrusionPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is HeatmapLayer -> (painter as HeatmapLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is HillshadeLayer -> (painter as HillshadeLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is RasterLayer -> (painter as RasterLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )

                        is SkyLayer -> (painter as SkyLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom
                        )
                    }
                }
            }
        }
    }
}

