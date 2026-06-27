package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.*
import ovh.plrapps.mapcompose.vector.utils.LruCache
import kotlinx.coroutines.sync.Mutex

class TileRenderer(
    configuration: MapLibreConfiguration,
    private val pathCache: LruCache<String, Any>,
    private val pathCacheMutex: Mutex,
    private val localPropCache: MutableMap<String, Map<String, Any?>>
) : BaseRenderer(configuration = configuration) {
    private val painters = mutableMapOf<Layer, BaseLayerPainter<*>>()

    suspend fun render(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: Layer,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        tileKey: String? = null
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
                            is FillLayer -> FillLayerPainter(pathCache, pathCacheMutex)
                            is HeatmapLayer -> HeatmapLayerPainter()
                            is HillshadeLayer -> HillshadeLayerPainter()
                            is LineLayer -> LineLayerPainter(pathCache, pathCacheMutex)
                            is RasterLayer -> RasterLayerPainter()
                            is SkyLayer -> SkyLayerPainter()
                        }
                    }

                for (feature in tileLayer.features) {
                    val featureIdKey = feature.id?.toString() ?: feature.hashCode().toString()
                    val propertyKey = if (tileKey != null) "$tileKey-${tileLayer.name}-$featureIdKey" else null
                    val featureProperties = if (propertyKey != null) {
                        localPropCache.getOrPut(propertyKey) { extractFeatureProperties(feature, tileLayer) }
                    } else {
                        extractFeatureProperties(feature, tileLayer)
                    }

                    val isShouldRenderFeature = shouldRenderFeature(feature, tileLayer, styleLayer, zoom, featureProperties)
                    if (!isShouldRenderFeature) continue

                    val featureKey = if (tileKey != null) "$tileKey-${styleLayer.id}-$featureIdKey" else null

                    when (styleLayer) {
                        is CircleLayer -> (painter as CircleLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is FillLayer -> (painter as FillLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is LineLayer -> (painter as LineLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
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
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is HeatmapLayer -> (painter as HeatmapLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is HillshadeLayer -> (painter as HillshadeLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is RasterLayer -> (painter as RasterLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )

                        is SkyLayer -> (painter as SkyLayerPainter).paint(
                            canvas = canvas,
                            feature = feature,
                            style = styleLayer,
                            canvasSize = canvasSize,
                            extent = extent,
                            zoom = zoom,
                            featureProperties = featureProperties,
                            actualZoom = actualZoom,
                            featureKey = featureKey
                        )
                    }
                }
            }
        }
    }
}

