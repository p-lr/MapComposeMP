package ovh.plrapps.mapcompose.maplibre.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import ovh.plrapps.mapcompose.maplibre.filter.FilterEvaluator
import ovh.plrapps.mapcompose.maplibre.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.*

class MapLayerRenderer(
    private val filterEvaluator: FilterEvaluator = FilterEvaluator(),
    private val textMeasurer: TextMeasurer
) {
    private val painters = mutableMapOf<Layer, BaseLayerPainter<*>>()

    fun render(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: Layer,
        collisionDetector: CollisionDetector,
        zoom: Double,
        canvasSize: Int
    ) {
        if (!isZoomInRange(styleLayer, zoom)) {
            // println("  missed by zoom")
            return
        }

        when (styleLayer) {
            is BackgroundLayer -> {
                painters
                    .getOrPut(styleLayer) {
                        BackgroundLayerPainter()
                    }.let {
                        it as BackgroundLayerPainter
                    }
                    .paint(
                        canvas = canvas,
                        collisionDetector = collisionDetector,
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
                        featureProperties = null
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
                    println("source-layer '${styleLayer.sourceLayer}' not found")
                    return
                }
                val extent = tileLayer.extent ?: 4096
                val painter = painters
                    .getOrPut(styleLayer) {
                        when (styleLayer) {
                            is BackgroundLayer -> throw IllegalStateException("BackgroundLayer cant be here")
                            is CircleLayer -> CircleLayerPainter()
                            is FillExtrusionLayer -> FillExtrusionPainter()
                            is FillLayer -> FillLayerPainter()
                            is HeatmapLayer -> HeatmapLayerPainter()
                            is HillshadeLayer -> HillshadeLayerPainter()
                            is LineLayer -> LineLayerPainter()
                            is RasterLayer -> RasterLayerPainter()
                            is SkyLayer -> SkyLayerPainter()
                            is SymbolLayer -> SymbolLayerPainter(textMeasurer = textMeasurer)
                        }
                    }

                for (feature in tileLayer.features) {
                    val isShouldRenderFeature = shouldRenderFeature(feature, tileLayer, styleLayer, zoom)
                    if (!isShouldRenderFeature) continue

                    val featureProperties = extractFeatureProperties(feature, tileLayer)

                    when (styleLayer) {
                        is CircleLayer -> (painter as CircleLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is FillLayer -> (painter as FillLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is LineLayer -> (painter as LineLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is SymbolLayer -> (painter as SymbolLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is BackgroundLayer -> throw IllegalStateException("BackgroundLayer cant be here")
                        is FillExtrusionLayer -> (painter as FillExtrusionPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is HeatmapLayer -> (painter as HeatmapLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is HillshadeLayer -> (painter as HillshadeLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is RasterLayer -> (painter as RasterLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )

                        is SkyLayer -> (painter as SkyLayerPainter).paint(
                            canvas = canvas,
                            collisionDetector = collisionDetector,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties
                        )
                    }
                }
            }
        }
    }

    private fun shouldRenderFeature(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
        styleLayer: Layer,
        zoom: Double
    ): Boolean {
        styleLayer.filter?.let { filter ->
            if (!filterEvaluator.evaluate(filter, feature, tileLayer)) {
                return false
            }
        }
        return true
    }

    private val MAX_ZOOM = 30.0

    private fun isZoomInRange(styleLayer: Layer, zoom: Double): Boolean {
        val minZoom = styleLayer.minzoom ?: 0.0
        val maxZoom = styleLayer.maxzoom ?: MAX_ZOOM
        return zoom in minZoom..maxZoom
    }

    fun extractFeatureProperties(feature: Tile.Feature, tileLayer: Tile.Layer): Map<String, Any?> {
        val props = mutableMapOf<String, Any?>()
        val keys = tileLayer.keys
        val values = tileLayer.values
        for (i in feature.tags.indices step 2) {
            val keyIdx = feature.tags[i]
            val valueIdx = feature.tags[i + 1]
            if (keyIdx < keys.size && valueIdx < values.size) {
                val key = keys[keyIdx]
                val value = values[valueIdx]
                props[key] =
                    value.stringValue ?: value.floatValue ?: value.doubleValue ?: value.intValue ?: value.uintValue
                            ?: value.sintValue ?: value.boolValue
            }
        }
        return props
    }
}

