package ovh.plrapps.mapcompose.vector.core

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readString
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.fullSize
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.core.TileMatrix
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.core.Viewport
import ovh.plrapps.mapcompose.core.VisibleTiles
import ovh.plrapps.mapcompose.core.VisibleWindow
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.utils.throttle
import ovh.plrapps.mapcompose.vector.data.extension.toBytes
import ovh.plrapps.mapcompose.vector.data.extension.toMVTViewport
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/**
 * The pixel size of the bitmap a vector tile is rasterized into.
 *
 * A tile is drawn into `mapState.tileSize * relativeScale` device pixels (see `TileCanvas`, where
 * `dstSize` is `tileSize / scaleForLevel` inside a `scale(scale)` transform), and `relativeScale` is
 * in `(2^(magnifyingFactor - 1), 2^magnifyingFactor]` because `VisibleTilesResolver.getLevel` rounds
 * the level up after subtracting the factor. Sizing against the larger of [density] and
 * `2^magnifyingFactor` is what guarantees the bitmap is never smaller than the area it covers, so a
 * tile is always minified, never stretched.
 *
 * Neither factor changes the *apparent* size of what is drawn -- the destination above is fixed by
 * the map's geometry. [density] only adds resolution, and [superSampling] adds more still, to be
 * filtered back down before the bitmap leaves the rasterizer. It is [magnifyingFactorForDensity],
 * applied to the map itself, that puts a style pixel on a density-independent pixel.
 */
internal fun vectorTileBitmapSize(
    tileSize: Int,
    density: Float,
    superSampling: Int,
    magnifyingFactor: Int,
): Int {
    val resolution = max(density, 2f.pow(magnifyingFactor.coerceAtLeast(0))).coerceAtLeast(1f)
    return (tileSize * resolution).toInt() * superSampling.coerceAtLeast(1)
}

/**
 * The magnifying factor that puts one style pixel on one density-independent pixel.
 *
 * A vector style is authored in CSS pixels, which is what a `Dp` is here -- the same unit
 * `PathApi`'s `width` and a marker's `DpOffset` are in. Tile content is drawn at
 * `tileSize * relativeScale` device pixels whatever the density is, so without this a style pixel
 * lands on one *device* pixel: the map renders at `1 / density` of its intended size while labels,
 * paths and markers stay at dp, which is what makes correctly-sized labels look oversized next to a
 * half-width road.
 *
 * Raising the factor drops the level `VisibleTilesResolver` picks, which also makes MapCompose ask
 * for the same tile `z` MapLibre would for the same view.
 */
internal fun magnifyingFactorForDensity(density: Float): Int =
    round(log2(density.coerceAtLeast(1f))).toInt().coerceAtLeast(0)

internal class VectorLayer(
    private val mapState: MapState,
    private val vectorTileStreamProvider: VectorTileStreamProvider,
    private val superSamplingFactor: Int = 1,
) {
    private val scope = mapState.scope

    val visibleTiles =
        mapState.tileCanvasState.visibleTiles.stateIn(scope, SharingStarted.Eagerly, null)
    val viewportInfoFlow = MutableStateFlow<ViewportInfo?>(null)

    init {
        listenForViewportUpdates()
    }

    suspend fun makeTileStreamProvider(): TileStreamProvider {
        val style = withContext(IODispatcher) {
            vectorTileStreamProvider.loadResources(vectorTileStreamProvider.styleUrl)?.buffered()?.readString()
        }

        val configuration = getMapLibreConfiguration(
            style = style ?: "",
            loadResource = vectorTileStreamProvider::loadResources
        ).getOrThrow()

        val rasterizer = VectorRasterizer(
            configuration = configuration,
            densityState = mapState.densityState,
            fontFamilyResolverState = mapState.fontFamilyResolverState,
            textMeasurerState = mapState.textMeasurerState,
            getTileStream = vectorTileStreamProvider::getTileStream
        )

        startSymbolsProcessing(rasterizer)

        return TileStreamProvider { row, col, zoomLvl ->
            val density = mapState.densityState.value ?: return@TileStreamProvider null
            val bitmapPx = vectorTileBitmapSize(
                tileSize = mapState.tileSize,
                density = density.density,
                superSampling = superSamplingFactor,
                magnifyingFactor = mapState.visibleTilesResolver.magnifyingFactor,
            )

            val imageBitmap = rasterizer.getTile(
                x = col,
                y = row,
                zoom = zoomLvl.toDouble(),
                tileSize = bitmapPx,
                superSampling = superSamplingFactor,
            )

            val bytes = imageBitmap.toBytes()
                ?: return@TileStreamProvider null

            Buffer().apply {
                write(bytes)
            }
        }
    }

    private fun startSymbolsProcessing(rasterizer: VectorRasterizer) {
        scope.launch {
            viewportInfoFlow
                .throttle(250)
                .collectLatest { viewportInfo ->
                    viewportInfo ?: return@collectLatest

                    val zoomLvl = viewportInfo.zoom
                    /* Not the same number as the tile bitmap size above, and deliberately so:
                     * symbols are drawn by SymbolComposer as a viewport overlay, so they are laid
                     * out in the pixel space a tile occupies *on screen*, not in the pixel space
                     * the tile bitmap is rasterized at.
                     *
                     * It has to be the real on-screen size rather than `mapState.tileSize`, because
                     * every length the symbol painters compare a tile's geometry against -- a
                     * label's own width, `symbol-spacing`, `text-padding` -- is in device pixels
                     * (one style pixel is one dp, times `density.density`). Laying out against the
                     * unscaled tile size made a tile's geometry `relativeScale` times too small, so
                     * `symbol-spacing` came out that many times too coarse and almost every road
                     * fell through to a single label. `fullWidth * scale` is the world's width in
                     * device pixels, the same quantity `mercatorToViewport` projects with. */
                    val worldPx = viewportInfo.fullWidth.toDouble() * viewportInfo.scale
                    val layoutPx = (worldPx / 2.0.pow(zoomLvl))
                        .toInt()
                        .coerceAtLeast(1)

                    val nextSymbols = rasterizer.produceSymbols(
                        viewport = viewportInfo.toMVTViewport(),
                        tileSize = layoutPx,
                        z = zoomLvl.toDouble()
                    ).getOrElse { e ->
                        println("[ERROR] produceSymbols(): ${e.message}")
                        return@collectLatest
                    }

                    rasterizer.updateSymbols(
                        nextSymbols = nextSymbols,
                        state = mapState,
                        viewportInfo = viewportInfo
                    )
                    mapState.symbolState.visiblePhases = viewportInfo.visiblePhases
                }
        }
    }

    private fun listenForViewportUpdates() {
        var lastViewport: Viewport? = null
        fun updateViewportInfo(visibleTiles: VisibleTiles, viewport: Viewport) {
            val visibleWindow = visibleTiles.visibleWindow
            val (mergedMatrix, leftVisible, rightVisible) = when (visibleWindow) {
                is VisibleWindow.InfiniteScrollX -> Triple(
                    mergeMatrices(
                        visibleWindow.tileMatrix,
                        visibleWindow.leftOverflow?.tileMatrix,
                        visibleWindow.rightOverflow?.tileMatrix
                    ),
                    visibleWindow.leftOverflow != null,
                    visibleWindow.rightOverflow != null
                )
                is VisibleWindow.BoundsConstrained -> Triple(visibleWindow.tileMatrix, false, false)
            }
            viewportInfoFlow.value = ViewportInfo(
                matrix = mergedMatrix,
                size = IntSize(
                    width = (viewport.right - viewport.left),
                    height = (viewport.bottom - viewport.top),
                ),
                angleRad = viewport.angleRad,
                pitch = 0f,
                zoom = visibleTiles.level,
                centroidX = mapState.centroidX,
                centroidY = mapState.centroidY,
                scale = mapState.scale,
                fullWidth = mapState.fullSize.width,
                fullHeight = mapState.fullSize.height,
                infiniteScrollX = leftVisible || rightVisible,
                visiblePhases = (if (leftVisible) -1 else 0)..(if (rightVisible) 1 else 0),
            )
        }

        mapState.addViewportChangeListener { viewport ->
            val currentVisibleTiles = visibleTiles.value
            if (currentVisibleTiles != null) {
                lastViewport = viewport
                updateViewportInfo(currentVisibleTiles, viewport)
            }
        }

        scope.launch {
            visibleTiles.collect { visibleTiles ->
                visibleTiles ?: return@collect
                val viewport = lastViewport ?: return@collect
                updateViewportInfo(visibleTiles, viewport)
            }
        }
    }

    private fun mergeMatrices(vararg matrices: TileMatrix?): TileMatrix {
        val result = mutableMapOf<Int, IntRange>()
        for (matrix in matrices) {
            matrix ?: continue
            for ((row, cols) in matrix) {
                val existing = result[row]
                result[row] = if (existing == null) cols else {
                    minOf(existing.first, cols.first)..maxOf(existing.last, cols.last)
                }
            }
        }
        return result
    }
}