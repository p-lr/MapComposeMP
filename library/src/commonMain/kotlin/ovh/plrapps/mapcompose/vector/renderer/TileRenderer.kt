package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.sync.Mutex
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.renderer.utils.CircleVertexGate
import ovh.plrapps.mapcompose.vector.renderer.utils.PatternBrushCache
import ovh.plrapps.mapcompose.vector.renderer.utils.evaluateSortKey
import ovh.plrapps.mapcompose.vector.renderer.utils.isInsideTile
import ovh.plrapps.mapcompose.vector.renderer.utils.layerOpacityOf
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
 * A `fill` or `line` layer that sets `*-layer-opacity` is drawn into a `saveLayer` and composited
 * once at that alpha, which is upstream's per-layer framebuffer pass (`draw_layer_opacity.ts`) --
 * see [ovh.plrapps.mapcompose.vector.renderer.utils.layerOpacityOf]. The spec default of `1` takes
 * no offscreen.
 *
 * Only the 2D layer types are drawn here. `symbol` is produced separately by [SymbolBucketBuilder] so
 * that collision detection can run across the whole viewport rather than per tile, and
 * `fill-extrusion` / `sky` are not implemented -- see the note in each painter for what each would
 * need.
 *
 * A `raster` layer is drawn from [rasterImage], and a `hillshade` or `color-relief` layer from
 * [demTile], rather than from [tile]: their sources serve images, not MVT, so
 * [ovh.plrapps.mapcompose.vector.core.VectorRasterizer] decodes them separately and passes what
 * covers this tile. `raster-fade-duration` is inert here; see [RasterLayerPainter], and see
 * [HillshadeLayerPainter] and [ColorReliefLayerPainter] for the two DEM layers' own divergences.
 *
 * A `heatmap` layer and a `circle` layer read [neighbours] in addition to [tile]: a kernel and a
 * disc both reach past the tile they belong to, and a tile is rasterized into its own bitmap here,
 * so the neighbouring tiles' points have to be drawn into this one. See [HeatmapLayerPainter] and
 * [ovh.plrapps.mapcompose.vector.renderer.utils.CircleVertexGate].
 *
 * When the source is *overzoomed* -- [tileRef] carries `span > 1`, so [tile] is an ancestor tile
 * covering `span x span` map tiles -- the geometry is decoded at `canvasSize * span` and the
 * destination is *translated* by `-(subX, subY) * canvasSize`. That is upstream's magnification of
 * a canonical tile (`reparseOverscaled`), and translating rather than scaling is what keeps every
 * style width -- `line-width`, `circle-radius`, a pattern's period -- in screen pixels. It also
 * makes `isInsideTile` the *canonical* tile's bounds, which is the rule upstream's
 * `CircleBucket.addFeature` applies: a vertex in a neighbouring sub-square is still drawn, and
 * merely clipped by the bitmap.
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

    /** Likewise for hillshade: one stateless instance, never routed through [painterFor]. */
    private val hillshadePainter = HillshadeLayerPainter()

    /** And for the other DEM-reading layer type. */
    private val colorReliefPainter = ColorReliefLayerPainter()

    /** And for heatmap, which paints a whole layer's points at once rather than one feature. */
    private val heatmapPainter = HeatmapLayerPainter()

    /** Circles are painted through their own reference, which carries the vertex gate overload. */
    private val circlePainter = CircleLayerPainter()

    /** Point decoding for the heatmap layer; the other layer types decode inside their painter. */
    private val geometryDecoders = GeometryDecoders()

    suspend fun render(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: Layer,
        zoom: Double,
        canvasSize: Int,
        actualZoom: Double,
        tileKey: String? = null,
        rasterImage: RasterTileImage? = null,
        demTile: DemTile? = null,
        tileY: Int = 0,
        tileX: Int = 0,
        neighbours: List<NeighbourTile> = emptyList(),
        tileRef: TileRef = TileRef.whole(z = zoom.toInt(), x = tileX, y = tileY),
    ) {
        if (!isLayerVisible(styleLayer)) return
        if (!isZoomInRange(styleLayer, zoom)) return

        /* The space a source's geometry is decoded into. Identical to the tile bitmap unless the
         * source is overzoomed, in which case it is the ancestor tile magnified over its span. */
        val span = tileRef.span.coerceAtLeast(1)
        val geometrySize = canvasSize * span
        val geometryTileX = if (span > 1) tileRef.x else tileX
        val geometryTileY = if (span > 1) tileRef.y else tileY

        /* Patterns are anchored to the world and sized in screen pixels, so they need to know which
         * tile they are being drawn into and how much it will be magnified. See PatternBrushCache.
         * The layer types drawn from decoded geometry are painted in the ancestor's space when the
         * source is overzoomed, so their phase is the ancestor's world offset. */
        val inGeometrySpace = styleLayer is FillLayer || styleLayer is LineLayer || styleLayer is CircleLayer
        patternBrushes.configure(
            tileX = if (inGeometrySpace) geometryTileX else tileX,
            tileY = if (inGeometrySpace) geometryTileY else tileY,
            canvasSize = if (inGeometrySpace) geometrySize else canvasSize,
            tileZoom = zoom,
            actualZoom = actualZoom,
            density = canvas.density,
        )

        when (styleLayer) {
            // Drawn elsewhere or not implemented; see the class KDoc.
            is SymbolLayer,
            is FillExtrusionLayer,
            is SkyLayer -> return

            is HeatmapLayer -> {
                heatmapPainter.paint(
                    canvas = canvas,
                    style = styleLayer,
                    points = heatmapPoints(
                        tile, neighbours, styleLayer, zoom, canvasSize, tileRef
                    ),
                    canvasSize = canvasSize,
                    actualZoom = actualZoom,
                )
            }

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

            is HillshadeLayer -> {
                // No DEM means the source had nothing for this tile -- below its minzoom, or the
                // fetch failed. Either way there is nothing to shade.
                demTile ?: return
                hillshadePainter.paint(
                    canvas = canvas,
                    style = styleLayer,
                    demTile = demTile,
                    canvasSize = canvasSize,
                    tileZ = zoom.toInt(),
                    tileY = tileY,
                    actualZoom = actualZoom,
                )
            }

            is ColorReliefLayer -> {
                // No DEM means the source had nothing for this tile -- below its minzoom, or the
                // fetch failed. Either way there is nothing to colour.
                demTile ?: return
                colorReliefPainter.paint(
                    canvas = canvas,
                    style = styleLayer,
                    demTile = demTile,
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

            is CircleLayer -> renderCircles(
                canvas = canvas,
                tile = tile,
                styleLayer = styleLayer,
                zoom = zoom,
                canvasSize = canvasSize,
                geometrySize = geometrySize,
                actualZoom = actualZoom,
                tileKey = tileKey,
                tileRef = tileRef,
                neighbours = neighbours,
            )

            is FillLayer,
            is LineLayer -> {
                if (tile == null || tile.layers.isEmpty()) return
                val tileLayer = tileLayerFor(tile, styleLayer) ?: return
                val extent = tileLayer.extent ?: DEFAULT_EXTENT
                val visible = visibleFeatures(
                    tileLayer, styleLayer, zoom, actualZoom, tileKey, geometrySize
                )
                if (visible.isEmpty()) return

                /* `*-layer-opacity` composites the whole layer once, after every feature is drawn,
                 * rather than scaling each feature's own alpha -- see [layerOpacityOf]. A
                 * `saveLayer` is upstream's per-layer framebuffer: the features accumulate inside
                 * it exactly as they do today, and the result is blitted at this alpha. Fully
                 * opaque is the spec default and allocates nothing. */
                val layerOpacity = layerOpacityOf(styleLayer, actualZoom)
                if (layerOpacity <= 0f) return
                val compositeLayer = layerOpacity < 1f

                /* A translate, never a scale: the geometry is already decoded at [geometrySize], and
                 * scaling here would multiply every style width by the span too. */
                val nativeCanvas = canvas.drawContext.canvas
                if (compositeLayer) {
                    /* Before the span translate, so the offscreen covers the tile bitmap rather
                     * than the ancestor's magnified space. */
                    nativeCanvas.saveLayer(
                        bounds = Rect(Offset.Zero, canvas.size),
                        paint = Paint().apply { alpha = layerOpacity },
                    )
                }
                if (span > 1) {
                    nativeCanvas.save()
                    nativeCanvas.translate(
                        -tileRef.subX.toFloat() * canvasSize,
                        -tileRef.subY.toFloat() * canvasSize,
                    )
                }
                try {
                    for (entry in visible) {
                        when (styleLayer) {
                            is FillLayer -> painterFor(styleLayer).paint(
                                canvas, entry.feature, styleLayer, geometrySize, extent, zoom,
                                entry.properties, actualZoom, entry.cacheKey
                            )

                            is LineLayer -> painterFor(styleLayer).paint(
                                canvas, entry.feature, styleLayer, geometrySize, extent, zoom,
                                entry.properties, actualZoom, entry.cacheKey
                            )
                        }
                    }
                } finally {
                    if (span > 1) nativeCanvas.restore()
                    if (compositeLayer) nativeCanvas.restore()
                }
            }
        }
    }

    /**
     * Draws a `circle` layer, from this tile **and** from the neighbouring tiles that were gathered.
     *
     * A circle spills over the tile boundary and this tile's bitmap is the clip, so the half that
     * falls outside has to be drawn by the neighbour it falls into -- see [CircleVertexGate], which
     * is what keeps a vertex from being drawn twice once both tiles carry it. The neighbours are the
     * same set the heatmap reads, gathered only when a circle layer actually draws at this zoom.
     *
     * A neighbour's features are keyed by nothing: [visibleFeatures]' cache keys name the tile being
     * rasterized, and reusing them for another tile's features would hand one feature's properties
     * to another's geometry.
     */
    private suspend fun renderCircles(
        canvas: DrawScope,
        tile: Tile?,
        styleLayer: CircleLayer,
        zoom: Double,
        canvasSize: Int,
        geometrySize: Int,
        actualZoom: Double,
        tileKey: String?,
        tileRef: TileRef,
        neighbours: List<NeighbourTile>,
    ) {
        val span = tileRef.span.coerceAtLeast(1)
        val covered = neighbours.mapTo(mutableSetOf()) { it.dx to it.dy }

        val nativeCanvas = canvas.drawContext.canvas
        if (span > 1) {
            nativeCanvas.save()
            nativeCanvas.translate(
                -tileRef.subX.toFloat() * canvasSize,
                -tileRef.subY.toFloat() * canvasSize,
            )
        }
        try {
            if (tile != null && tile.layers.isNotEmpty()) {
                paintCircles(
                    canvas, tile, styleLayer, zoom, geometrySize, actualZoom, tileKey,
                    CircleVertexGate(coveredDirections = covered)
                )
            }
            for (neighbour in neighbours) {
                paintCircles(
                    canvas, neighbour.tile, styleLayer, zoom, geometrySize, actualZoom,
                    tileKey = null,
                    gate = CircleVertexGate.forNeighbour(neighbour.dx, neighbour.dy, geometrySize)
                )
            }
        } finally {
            if (span > 1) nativeCanvas.restore()
        }
    }

    /** One tile's contribution to a `circle` layer, gated by [gate]. */
    private suspend fun paintCircles(
        canvas: DrawScope,
        tile: Tile,
        styleLayer: CircleLayer,
        zoom: Double,
        geometrySize: Int,
        actualZoom: Double,
        tileKey: String?,
        gate: CircleVertexGate,
    ) {
        val tileLayer = tileLayerFor(tile, styleLayer) ?: return
        val extent = tileLayer.extent ?: DEFAULT_EXTENT
        val visible = visibleFeatures(tileLayer, styleLayer, zoom, actualZoom, tileKey, geometrySize)
        for (entry in visible) {
            circlePainter.paint(
                canvas, entry.feature, styleLayer, geometrySize, extent, zoom, entry.properties,
                actualZoom, entry.cacheKey, gate
            )
        }
    }

    /**
     * The features of [tileLayer] that [styleLayer] draws, in draw order.
     *
     * Ordering is the tile's own feature order unless the layer declares a `*-sort-key`, which
     * MapLibre sorts ascending so that a higher key draws on top. A [tileKey] of `null` means the
     * caches are skipped -- there is no key that names this tile.
     */
    private fun visibleFeatures(
        tileLayer: Tile.Layer,
        styleLayer: Layer,
        zoom: Double,
        actualZoom: Double,
        tileKey: String?,
        geometrySize: Int,
    ): List<VisibleFeature> {
        // Feature geometry is only decoded when a `within`/`distance` expression reads it.
        val needGeometry = styleLayer.filter?.filter?.needGeometry == true

        val visible = ArrayList<VisibleFeature>(tileLayer.features.size)
        tileLayer.features.forEachIndexed { index, feature ->
            /* The feature's position in the tile layer, not its `id` or its `hashCode`.
             * An MVT feature usually carries no `id`, and a 32-bit hash over a few thousand
             * features in one layer collides often enough to matter -- two features sharing
             * a key share both the cached `EvalFeature` and the cached geometry, so one is
             * drawn with the other's properties and the other's shape. The decode order is
             * deterministic and the decoded ancestor tile is shared by every sub-square, so
             * the index is stable exactly where the caches need it to be. */
            val featureIdKey = "f$index"
            val propertyKey = if (tileKey != null) "$tileKey-${tileLayer.name}-$featureIdKey" else null
            val featureProperties = if (propertyKey != null) {
                localPropCache.getOrPut(propertyKey) { buildEvalFeature(feature, tileLayer, needGeometry) }
            } else {
                buildEvalFeature(feature, tileLayer, needGeometry)
            }

            if (!shouldRenderFeature(feature, tileLayer, styleLayer, zoom, featureProperties)) {
                return@forEachIndexed
            }

            /* [geometrySize] is what the polylines the key caches are measured in. It is a
             * pure function of the span today, which the tile key already carries, but
             * nothing in the key said so. */
            val featureKey = if (tileKey != null) {
                "$tileKey-$geometrySize-${styleLayer.id}-$featureIdKey"
            } else {
                null
            }
            visible.add(VisibleFeature(feature, featureProperties, featureKey))
        }

        sortKeyOf(styleLayer)?.let { sortKey ->
            visible.sortBy { evaluateSortKey(sortKey, it.properties, actualZoom) }
        }
        return visible
    }

    /**
     * Every point feature that contributes to [styleLayer]'s density field, in this tile's canvas
     * space.
     *
     * The neighbouring tiles are walked exactly as the tile itself is -- same source layer, same
     * filter -- and only their offset differs, so a point that heats this tile from outside is
     * gated the same way the tile's own points are.
     */
    private fun heatmapPoints(
        tile: Tile?,
        neighbours: List<NeighbourTile>,
        styleLayer: HeatmapLayer,
        zoom: Double,
        canvasSize: Int,
        tileRef: TileRef,
    ): List<HeatmapPoint> {
        val points = mutableListOf<HeatmapPoint>()
        if (tile != null) addHeatmapPoints(points, tile, 0, 0, styleLayer, zoom, canvasSize, tileRef)
        for (neighbour in neighbours) {
            addHeatmapPoints(
                points, neighbour.tile, neighbour.dx, neighbour.dy, styleLayer, zoom, canvasSize, tileRef
            )
        }
        return points
    }

    /**
     * The heatmap is the one layer type that is not drawn through a translated canvas: its density
     * field is a quarter of the *bitmap* in each axis, so the points have to arrive in bitmap space.
     * The ancestor's magnification therefore shows up here as the decode size and an explicit
     * offset, and a neighbour is a neighbour of the ancestor, one [geometrySize] away.
     */
    private fun addHeatmapPoints(
        into: MutableList<HeatmapPoint>,
        tile: Tile,
        dx: Int,
        dy: Int,
        styleLayer: HeatmapLayer,
        zoom: Double,
        canvasSize: Int,
        tileRef: TileRef,
    ) {
        val tileLayer = tileLayerFor(tile, styleLayer) ?: return
        val extent = tileLayer.extent ?: DEFAULT_EXTENT
        val needGeometry = styleLayer.filter?.filter?.needGeometry == true
        val span = tileRef.span.coerceAtLeast(1)
        val geometrySize = canvasSize * span
        val offsetX = dx.toDouble() * geometrySize - tileRef.subX.toDouble() * canvasSize
        val offsetY = dy.toDouble() * geometrySize - tileRef.subY.toDouble() * canvasSize

        for (feature in tileLayer.features) {
            if (feature.type != Tile.GeomType.POINT) continue
            val properties = buildEvalFeature(feature, tileLayer, needGeometry)
            if (!shouldRenderFeature(feature, tileLayer, styleLayer, zoom, properties)) continue

            val decoded = geometryDecoders.decodePoint(
                feature.geometry, extent = extent, canvasSize = geometrySize
            )
            for (point in decoded) {
                // See `isInsideTile`: a point the MVT buffer duplicated into a neighbouring tile
                // would otherwise be accumulated twice, once from each tile carrying it.
                if (!isInsideTile(point.x, point.y, geometrySize)) continue
                into.add(HeatmapPoint(point.x + offsetX, point.y + offsetY, properties))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Layer> painterFor(styleLayer: T): BaseLayerPainter<T> =
        painters.getOrPut(styleLayer) {
            when (styleLayer) {
                is BackgroundLayer -> BackgroundLayerPainter(configuration.spriteManager, patternBrushes)
                is CircleLayer -> circlePainter
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
