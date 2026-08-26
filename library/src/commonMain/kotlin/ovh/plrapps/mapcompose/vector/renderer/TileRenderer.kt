package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.evaluateSortKey
import ovh.plrapps.mapcompose.vector.renderer.utils.sortKeyOf
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.*
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.utils.LruCache

/**
 * Rasterizes one style layer into one tile bitmap.
 *
 * Layers arrive in style order and are drawn in that order; within a layer, features keep their
 * order in the tile unless the layer declares a `*-sort-key`, which MapLibre sorts by ascending so
 * that a higher key draws on top.
 *
 * Only the 2D layer types are drawn here. `symbol` is produced separately by [SymbolsProducer] so
 * that collision detection can run across the whole viewport rather than per tile, and
 * `hillshade` / `heatmap` / `fill-extrusion` / `sky` are not implemented -- see the note in each
 * painter for what each would need.
 *
 * A `raster` layer is drawn from [rasterImage] rather than from [tile]: its source serves images,
 * not MVT, so [ovh.plrapps.mapcompose.vector.core.VectorRasterizer] decodes it separately and passes
 * the crop that covers this tile. `raster-fade-duration` is inert here; see [RasterLayerPainter].
 */
class TileRenderer(
    configuration: MapLibreConfiguration,
    private val pathCache: LruCache<String, Any>,
    private val pathCacheMutex: Mutex,
    private val localPropCache: MutableMap<String, EvalFeature>
) : BaseRenderer(configuration = configuration) {
    private val painters = mutableMapOf<Layer, BaseLayerPainter<*>>()
    private val patternBrushes = PatternBrushCache()

    /** Stateless, and outside [BaseLayerPainter], so one instance serves every raster layer. */
    private val rasterPainter = RasterLayerPainter()

    suspend fun render(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: Layer,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        tileKey: String? = null,
        rasterImage: RasterTileImage? = null
    ) {
        if (!isLayerVisible(styleLayer)) return
        if (!isZoomInRange(styleLayer, zoom)) return

        when (styleLayer) {
            // Drawn elsewhere or not implemented; see the class KDoc.
            is SymbolLayer,
            is FillExtrusionLayer,
            is HeatmapLayer,
            is HillshadeLayer,
            is SkyLayer -> return

            is RasterLayer -> {
                // No image means the source had nothing for this tile -- below its minzoom, or the
                // fetch failed. Either way there is nothing to draw.
                rasterImage ?: return
                rasterPainter.paint(
                    canvas = canvas,
                    style = styleLayer,
                    image = rasterImage,
                    canvasSize = canvasSize,
                    actualZoom = actualZoom,
                )
            }

            is BackgroundLayer -> {
                painterFor(styleLayer).paint(
                    canvas = canvas,
                    feature = EMPTY_FEATURE,
                    style = styleLayer,
                    canvasSize = canvasSize,
                    extent = DEFAULT_EXTENT,
                    zoom = zoom,
                    featureProperties = null,
                    actualZoom = actualZoom,
                )
            }

            is CircleLayer,
            is FillLayer,
            is LineLayer -> {
                if (tile == null || tile.layers.isEmpty()) return
                val tileLayer = tile.layers.find { it.name == styleLayer.sourceLayer } ?: return
                val extent = tileLayer.extent ?: DEFAULT_EXTENT

                // Feature geometry is only decoded when a `within`/`distance` expression reads it.
                val needGeometry = styleLayer.filter?.filter?.needGeometry == true

                val visible = ArrayList<VisibleFeature>(tileLayer.features.size)
                for (feature in tileLayer.features) {
                    val featureIdKey = feature.id?.toString() ?: feature.hashCode().toString()
                    val propertyKey = if (tileKey != null) "$tileKey-${tileLayer.name}-$featureIdKey" else null
                    val featureProperties = if (propertyKey != null) {
                        localPropCache.getOrPut(propertyKey) { buildEvalFeature(feature, tileLayer, needGeometry) }
                    } else {
                        buildEvalFeature(feature, tileLayer, needGeometry)
                    }

                    if (!shouldRenderFeature(feature, tileLayer, styleLayer, zoom, featureProperties)) continue

                    val featureKey = if (tileKey != null) "$tileKey-${styleLayer.id}-$featureIdKey" else null
                    visible.add(VisibleFeature(feature, featureProperties, featureKey))
                }
                if (visible.isEmpty()) return

                sortKeyOf(styleLayer)?.let { sortKey ->
                    visible.sortBy { evaluateSortKey(sortKey, it.properties, actualZoom) }
                }

                for (entry in visible) {
                    when (styleLayer) {
                        is CircleLayer -> painterFor(styleLayer).paint(
                            canvas, entry.feature, styleLayer, canvasSize, extent, zoom,
                            entry.properties, actualZoom, entry.cacheKey
                        )

                        is FillLayer -> painterFor(styleLayer).paint(
                            canvas, entry.feature, styleLayer, canvasSize, extent, zoom,
                            entry.properties, actualZoom, entry.cacheKey
                        )

                        is LineLayer -> painterFor(styleLayer).paint(
                            canvas, entry.feature, styleLayer, canvasSize, extent, zoom,
                            entry.properties, actualZoom, entry.cacheKey
                        )
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Layer> painterFor(styleLayer: T): BaseLayerPainter<T> =
        painters.getOrPut(styleLayer) {
            when (styleLayer) {
                is BackgroundLayer -> BackgroundLayerPainter(configuration.spriteManager, patternBrushes)
                is CircleLayer -> CircleLayerPainter()
                is FillLayer -> FillLayerPainter(
                    pathCache, pathCacheMutex, configuration.spriteManager, patternBrushes
                )
                is LineLayer -> LineLayerPainter(
                    pathCache, pathCacheMutex, configuration.spriteManager, patternBrushes
                )
                else -> throw IllegalStateException("no painter for layer type '${styleLayer.type}'")
            }
        } as BaseLayerPainter<T>

    private class VisibleFeature(
        val feature: Tile.Feature,
        val properties: EvalFeature?,
        val cacheKey: String?,
    )

    private companion object {
        const val DEFAULT_EXTENT = 4096

        /** `background` has no source, so its painter is handed a feature that carries nothing. */
        val EMPTY_FEATURE = Tile.Feature(
            id = -1,
            type = Tile.GeomType.POINT,
            geometry = emptyList(),
            tags = emptyList(),
        )
    }
}
