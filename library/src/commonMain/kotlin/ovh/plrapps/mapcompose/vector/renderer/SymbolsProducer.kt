package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.utils.MergeableFeature
import ovh.plrapps.mapcompose.vector.renderer.utils.mergeLines
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE
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

    /** A `symbol-placement: line` feature held back until the layer's lines have been merged. */
    private class LineFeature(
        val feature: Tile.Feature,
        val featureProperties: EvalFeature?,
        val extent: Int,
        val id: String,
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
        if (!isLayerVisible(styleLayer)) return emptyList()

        if (!isZoomInRange(styleLayer, zoom)) return emptyList()

        if (tile == null || tile.layers.isEmpty()) return emptyList()

        val tileLayer = tileLayerFor(tile, styleLayer)

        if (tileLayer == null) {
            return emptyList()
        }

        // The sprite and the text of the sprite MAY be in different features, but in the same tile,
        // because their points match. Therefore, this is one element, but in what order they were drawn,
        // we do not know. Therefore, if the points match, then the text should be placed under the sprite, and if it is a sprite, then draw it above the text
        val symbols = mutableListOf<Symbol>()

        /* Upstream's `SymbolBucket.compareText`, which `anchorIsTooClose` reads: bucket-scoped, i.e.
         * one map per tile per style layer, which is exactly this call. */
        val compareText = mutableMapOf<String, MutableList<Pair<Float, Float>>>()

        /* `symbol-placement: line` features are held back so their lines can be merged first --
         * upstream does it in `SymbolBucket.populate`, before layout sees any feature: "Merge
         * adjacent lines with the same text to improve labeling. It's better to place labels on one
         * long line than on many short segments." */
        val lineFeatures = mutableListOf<MergeableFeature<LineFeature>>()

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
            val id = feature.id?.toString() ?: "unknown_${feature.hashCode()}"

            if (feature.type != Tile.GeomType.POINT &&
                symbolsPainter.symbolPlacementOf(styleLayer, featureProperties, actualZoom) == SYMBOL_PLACEMENT_LINE
            ) {
                val lines = symbolsPainter.decodeLines(feature, extent = extent, canvasSize = canvasSize)
                if (lines != null) {
                    lineFeatures += MergeableFeature(
                        text = symbolsPainter.mergeTextOf(styleLayer, featureProperties, actualZoom),
                        lines = lines.mapTo(mutableListOf()) { it.toMutableList() },
                        value = LineFeature(feature, featureProperties, extent, id),
                    )
                    continue
                }
            }

            symbolsPainter.produceSymbol(
                id = id,
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
                layerIndex = layerIndex,
                compareText = compareText,
            ).let { symbol ->
                symbols.addAll(symbol)
            }
        }

        for (merged in mergeLines(lineFeatures)) {
            val held = merged.value
            symbolsPainter.produceSymbol(
                id = held.id,
                feature = held.feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = held.extent,
                zoom = zoom,
                featureProperties = held.featureProperties,
                actualZoom = actualZoom,
                tileX = tileX,
                tileY = tileY,
                density = density,
                layerIndex = layerIndex,
                preDecodedLines = merged.lines,
                compareText = compareText,
            ).let { symbol ->
                symbols.addAll(symbol)
            }
        }

        return symbols
    }
}
