package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.utils.LruCache

class SymbolsProducer(
    configuration: MapLibreConfiguration,
    private val textMeasurer: MutableStateFlow<TextMeasurer?>,
    private val pathCache: LruCache<String, Any>,
    private val pathCacheMutex: Mutex
) : BaseRenderer(configuration = configuration) {

    val symbolsPainter = SymbolLayerPainter(
        textMeasurerState = textMeasurer,
        spriteManager = configuration.spriteManager,
        configuration = configuration,
        pathCache = pathCache,
        mutex = pathCacheMutex
    )

    suspend fun produce(
        tile: Tile?,
        styleLayer: SymbolLayer,
        layerIndex: Int = 0,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        tileX: Int = 0,
        tileY: Int = 0,
        density: Density,
        localPropCache: MutableMap<String, EvalFeature>
    ): List<Symbol> {
        if (!isZoomInRange(styleLayer, zoom)) {
//            println("  missed by zoom")
            return emptyList()
        }

        if (tile == null || tile.layers.isEmpty()) return emptyList()

        val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer }

        if (tileLayer == null) {
            return emptyList()
        }

        // The sprite and the text of the sprite MAY be in different features, but in the same tile,
        // because their points match. Therefore, this is one element, but in what order they were drawn,
        // we do not know. Therefore, if the points match, then the text should be placed under the sprite, and if it is a sprite, then draw it above the text
        val symbols = mutableListOf<Symbol>()

        // Feature geometry is only decoded when a `within`/`distance` expression reads it.
        val needGeometry = styleLayer.filter?.filter?.needGeometry == true

        for (feature in tileLayer.features) {
            val featureIdKey = feature.id?.toString() ?: feature.hashCode().toString()
            val propertyKey = if (tileX != 0 || tileY != 0) "T-$tileX-$tileY-${tileLayer.name}-$featureIdKey" else null
            val featureProperties = if (propertyKey != null) {
                localPropCache.getOrPut(propertyKey) { buildEvalFeature(feature, tileLayer, needGeometry) }
            } else {
                buildEvalFeature(feature, tileLayer, needGeometry)
            }

            val isShouldRenderFeature = shouldRenderFeature(feature, tileLayer, styleLayer, zoom, featureProperties)
            if (!isShouldRenderFeature) continue

            val extent = tileLayer.extent ?: 4096

            symbolsPainter.produceSymbol(
                id = feature.id?.toString() ?: "unknown_${feature.hashCode()}",
                feature = feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = extent,
                zoom = zoom,
                featureProperties = featureProperties,
                actualZoom = actualZoom,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex
            ).let { symbol->
                symbols.addAll(symbol)
            }
        }
        return symbols
    }
}