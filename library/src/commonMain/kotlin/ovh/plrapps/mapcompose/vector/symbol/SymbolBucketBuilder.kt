package ovh.plrapps.mapcompose.vector.symbol

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.renderer.BaseRenderer
import ovh.plrapps.mapcompose.vector.renderer.utils.MergeableFeature
import ovh.plrapps.mapcompose.vector.renderer.utils.mergeLines
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.SYMBOL_PLACEMENT_LINE
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.utils.LruCache

/**
 * The layout pass: one style layer over one canonical tile becomes one [SymbolBucket].
 *
 * This is upstream's `SymbolBucket.populate` plus `performSymbolLayout`
 * (`data/bucket/symbol_bucket.ts`, `symbol/symbol_layout.ts`). Everything it produces is
 * view-independent -- it is laid out against [LAYOUT_TILE_SIZE] at the tile's own integer zoom, not
 * against the size the tile happens to occupy on screen -- so a bucket outlives every pan, rotation
 * and fractional zoom, and only [Placement] runs again.
 */
internal class SymbolBucketBuilder(
    configuration: MapLibreConfiguration,
    private val textMeasurer: MutableStateFlow<TextMeasurer?>,
    private val pathCache: LruCache<String, Any>,
    private val pathCacheMutex: Mutex
) : BaseRenderer(configuration = configuration) {

    val symbolsLayout = SymbolLayerLayout(
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

    suspend fun build(
        tile: Tile?,
        styleLayer: SymbolLayer,
        layerIndex: Int,
        sourceName: String,
        ref: TileRef,
        /** The integer zoom the map is showing, which is the bucket's own -- upstream's `overscaledZ`. */
        bucketZoom: Int,
        density: Density,
        localPropCache: MutableMap<String, EvalFeature>
    ): SymbolBucket {
        val zoom = bucketZoom.toDouble()
        val canvasSize = layoutTileSize(density.density, ref.span)
        val sizes = symbolSizesFor(styleLayer, zoom)
        /* Layer-level, so it is read once here rather than per feature -- see [SymbolOrdering]. */
        val ordering = symbolOrderingFor(styleLayer, zoom)
        val empty = SymbolBucket(
            ref = ref,
            layerIndex = layerIndex,
            layerId = styleLayer.id,
            sourceName = sourceName,
            zoom = bucketZoom,
            canvasSize = canvasSize,
            textSizeData = sizes.textSizeData,
            iconSizeData = sizes.iconSizeData,
            instances = emptyList(),
            ordering = ordering,
        )

        if (!isLayerVisible(styleLayer)) return empty
        if (!isZoomInRange(styleLayer, zoom)) return empty
        if (tile == null || tile.layers.isEmpty()) return empty

        val tileLayer = tileLayerFor(tile, styleLayer) ?: return empty

        // The sprite and the text of the sprite MAY be in different features, but in the same tile,
        // because their points match. Therefore, this is one element, but in what order they were drawn,
        // we do not know. Therefore, if the points match, then the text should be placed under the sprite, and if it is a sprite, then draw it above the text
        val symbols = mutableListOf<SymbolInstance>()

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
            val propertyKey = if (ref.x != 0 || ref.y != 0) "T-${ref.x}-${ref.y}-${tileLayer.name}-$featureIdKey" else null
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
                symbolsLayout.symbolPlacementOf(styleLayer, featureProperties, zoom) == SYMBOL_PLACEMENT_LINE
            ) {
                val lines = symbolsLayout.decodeLines(feature, extent = extent, canvasSize = canvasSize)
                if (lines != null) {
                    lineFeatures += MergeableFeature(
                        text = symbolsLayout.mergeTextOf(styleLayer, featureProperties, zoom),
                        lines = lines.mapTo(mutableListOf()) { it.toMutableList() },
                        value = LineFeature(feature, featureProperties, extent, id),
                    )
                    continue
                }
            }

            symbols += symbolsLayout.produceSymbol(
                id = id,
                feature = feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = extent,
                tileZ = ref.z.toDouble(),
                featureProperties = featureProperties,
                actualZoom = zoom,
                tileX = ref.x,
                tileY = ref.y,
                density = density,
                layerIndex = layerIndex,
                sizes = sizes,
                compareText = compareText,
            )
        }

        for (merged in mergeLines(lineFeatures)) {
            val held = merged.value
            symbols += symbolsLayout.produceSymbol(
                id = held.id,
                feature = held.feature,
                style = styleLayer,
                canvasSize = canvasSize,
                extent = held.extent,
                tileZ = ref.z.toDouble(),
                featureProperties = held.featureProperties,
                actualZoom = zoom,
                tileX = ref.x,
                tileY = ref.y,
                density = density,
                layerIndex = layerIndex,
                sizes = sizes,
                preDecodedLines = merged.lines,
                compareText = compareText,
            )
        }

        return SymbolBucket(
            ref = ref,
            layerIndex = layerIndex,
            layerId = styleLayer.id,
            sourceName = sourceName,
            zoom = bucketZoom,
            canvasSize = canvasSize,
            textSizeData = sizes.textSizeData,
            iconSizeData = sizes.iconSizeData,
            instances = symbols,
            ordering = ordering,
        )
    }
}

/**
 * The layer's `text-size` and `icon-size` reduced to what the placement pass needs; see
 * [SymbolSizes] for why the bracket is `bucketZoom - 1`.
 */
internal fun symbolSizesFor(styleLayer: SymbolLayer, bucketZoom: Double): SymbolSizes {
    val tileZoom = bucketZoom - 1.0
    return SymbolSizes(
        tileZoom = tileZoom,
        textSizeData = getSizeData(tileZoom, styleLayer.layout?.textSize, StyleSpecDefaults.TEXT_SIZE),
        iconSizeData = getSizeData(tileZoom, styleLayer.layout?.iconSize, StyleSpecDefaults.ICON_SIZE),
    )
}
