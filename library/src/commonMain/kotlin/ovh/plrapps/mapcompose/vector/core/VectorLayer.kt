package ovh.plrapps.mapcompose.vector.core

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
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
import ovh.plrapps.mapcompose.vector.symbol.SymbolBucket
import ovh.plrapps.mapcompose.vector.symbol.fractionalZoom
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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

    /** The layout pass's output, re-placed by every viewport update until the tile set changes. */
    private val symbolBuckets = MutableStateFlow<List<SymbolBucket>>(emptyList())

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

    /**
     * Symbol layout and symbol placement, on their own cadences.
     *
     * Layout is a function of the tile set and the integer zoom alone, so it runs only when those
     * change and its buckets are cached. Placement is *considered* on every viewport update, but
     * `VectorRasterizer.place` decides whether to actually run one, the way upstream's
     * `Style._updatePlacement` does -- at most once per fade duration. What keeps up with the map in
     * between is the draw pass, which re-projects and re-scales the held placement every frame.
     */
    private fun startSymbolsProcessing(rasterizer: VectorRasterizer) {
        /* Layout requests, conflated -- and, like the placement requests below, deliberately *not*
         * consumed under `collectLatest`.
         *
         * A layout run awaits every missing tile's fetch, so it lasts as long as the network does,
         * while its key changes at every tile-matrix shift and every integer zoom step. Cancelling
         * the run in flight therefore meant that through a gesture it was killed every
         * `LAYOUT_THROTTLE_MS` and never published at all: placement kept running against a bucket
         * set from before the gesture, and the fetches the cancelled run had already completed were
         * discarded with it (`fetchTile` rethrows `CancellationException` before it caches the
         * bytes), so the next run was no more likely to finish than the last. When one finally did
         * land -- usually only once the gesture stopped -- the whole bucket set swapped in a single
         * step, and every identity that did not survive that swap faded out and back in.
         *
         * Newest still wins; what changes is that the run already under way is allowed to finish. */
        val layoutRequests = Channel<ViewportInfo>(Channel.CONFLATED)

        scope.launch {
            viewportInfoFlow
                .filterNotNull()
                .distinctUntilChangedBy {
                    LayoutKey(zoom = it.zoom, matrix = it.matrix, overflowMatrices = it.overflowMatrices)
                }
                .throttle(LAYOUT_THROTTLE_MS)
                .collect { layoutRequests.send(it) }
        }

        scope.launch {
            var request = layoutRequests.receive()
            while (true) {
                var outcome = runLayout(rasterizer, request)

                /* A pass whose tiles did not all arrive is retried, because nothing else will ask
                 * for them: the layout only re-runs when the tile set or the integer zoom changes,
                 * so a tile that failed once would keep the bucket the previous pass published --
                 * or have none at all, the first time round -- until the map moved. Bounded, and
                 * backing off, because a tile the server simply does not have never resolves. */
                var attempt = 0
                var next = layoutRequests.tryReceive().getOrNull()
                while (next == null && outcome?.isComplete == false && attempt < LAYOUT_RETRY_LIMIT) {
                    delay((LAYOUT_THROTTLE_MS shl attempt).milliseconds)
                    attempt += 1
                    next = layoutRequests.tryReceive().getOrNull()
                    if (next != null) break
                    outcome = runLayout(rasterizer, request)
                }
                request = next ?: layoutRequests.receive()
            }
        }

        /* Placement requests, conflated: only the newest matters, and it is deliberately *not*
         * consumed under `collectLatest`.
         *
         * `VectorRasterizer.place` is a `withContext` block, and cancelling one throws away the
         * finished `PlacementResult` while every side effect it already applied -- the cross-tile
         * index, `previousPlacement`, the opacities it advanced -- stands. Under `collectLatest`
         * that happened on every viewport update, so through a gesture the fades kept advancing
         * invisibly while the screen held a placement from several cycles back, and the one that
         * finally landed made every label jump instead of fade. A conflated channel keeps the
         * "newest wins" behaviour without cancelling the pass that is already running. */
        val placementRequests = Channel<Pair<List<SymbolBucket>, ViewportInfo>>(Channel.CONFLATED)

        scope.launch {
            combine(symbolBuckets, viewportInfoFlow.filterNotNull()) { buckets, viewportInfo ->
                buckets to viewportInfo
            }
                .throttle(PLACEMENT_THROTTLE_MS)
                .collect { placementRequests.send(it) }
        }

        scope.launch {
            var request = placementRequests.receive()
            while (true) {
                val (buckets, viewportInfo) = request
                mapState.symbolState.visiblePhases = viewportInfo.visiblePhases

                var outcome = rasterizer.place(buckets, viewportInfo, nowMillis())
                outcome.result?.let { mapState.symbolState.placement = it }

                /* A deferred cycle has to be picked up once its window lapses, or a gesture that
                 * ends would leave the last change unplaced -- upstream gets that for free by
                 * re-entering `_updatePlacement` every render frame. A newer request supersedes the
                 * retry, and `place` stops asking for one as soon as a cycle commits with nothing
                 * further pending. */
                var next = placementRequests.tryReceive().getOrNull()
                while (next == null && outcome.retryInMs > 0L) {
                    delay(outcome.retryInMs.milliseconds)
                    next = placementRequests.tryReceive().getOrNull()
                    if (next != null) break
                    outcome = rasterizer.place(buckets, viewportInfo, nowMillis())
                    outcome.result?.let { mapState.symbolState.placement = it }
                }
                request = next ?: placementRequests.receive()
            }
        }
    }

    /**
     * One layout pass, publishing what it built.
     *
     * `viewportInfo` is *one* snapshot, and the zoom is read from the same one the tile matrix came
     * from. This used to lay out the throttled key's zoom against whatever `viewportInfoFlow.value`
     * held when the throttle let it through, and those disagree for up to [LAYOUT_THROTTLE_MS]
     * across an integer zoom boundary: the row/col indices of one level were resolved as tiles of
     * the other, so a run built buckets for the wrong tiles and the next run replaced them -- every
     * label on screen losing its `crossTileID` twice per zoom step.
     *
     * An empty result is published only when the pass was *complete*. A pass that resolved nothing
     * because every fetch failed is not the statement "there are no symbols here", and publishing it
     * would unplace every label on the map for as long as the failure lasts.
     */
    private suspend fun runLayout(
        rasterizer: VectorRasterizer,
        viewportInfo: ViewportInfo,
    ): LayoutOutcome? {
        val outcome = rasterizer.layoutBuckets(
            viewport = viewportInfo.toMVTViewport(),
            z = viewportInfo.zoom.toDouble(),
        ).getOrElse { e ->
            println("[ERROR] layoutBuckets(): ${e.message}")
            return null
        }
        if (outcome.buckets.isNotEmpty() || outcome.isComplete) symbolBuckets.value = outcome.buckets
        return outcome
    }

    /** What the layout pass depends on, and nothing else: which tiles, at what integer zoom. */
    private data class LayoutKey(
        val zoom: Int,
        val matrix: TileMatrix,
        val overflowMatrices: List<TileMatrix>,
    )

    private fun listenForViewportUpdates() {
        var lastViewport: Viewport? = null
        fun updateViewportInfo(visibleTiles: VisibleTiles, viewport: Viewport) {
            val visibleWindow = visibleTiles.visibleWindow
            /* The wrap-around windows are kept *beside* the main one, never merged into it. A
             * `TileMatrix` is one column range per row, so folding an overflow range of, say,
             * `250..255` into a main range of `0..5` yields `0..255` -- every tile in the row, for
             * every symbol layer in the style. That alone blows past any bucket or tile cache and
             * puts the layout pass permanently in the rebuild-everything state this change exists to
             * get it out of. */
            val (mainMatrix, overflowMatrices, leftVisible, rightVisible) = when (visibleWindow) {
                is VisibleWindow.InfiniteScrollX -> VisibleMatrices(
                    main = visibleWindow.tileMatrix,
                    overflow = listOfNotNull(
                        visibleWindow.leftOverflow?.tileMatrix,
                        visibleWindow.rightOverflow?.tileMatrix,
                    ),
                    leftVisible = visibleWindow.leftOverflow != null,
                    rightVisible = visibleWindow.rightOverflow != null,
                )
                is VisibleWindow.BoundsConstrained ->
                    VisibleMatrices(visibleWindow.tileMatrix, emptyList(), false, false)
            }
            /* Where between two integer zooms the map really is. Layout stays pinned to the
             * integer level; this is what `text-size` and `icon-size` are evaluated at. */
            val fractionalZoom = fractionalZoom(
                fullWidth = mapState.fullSize.width,
                scale = mapState.scale,
                density = mapState.densityState.value?.density ?: 1f,
            )

            viewportInfoFlow.value = ViewportInfo(
                matrix = mainMatrix,
                overflowMatrices = overflowMatrices,
                size = IntSize(
                    width = (viewport.right - viewport.left),
                    height = (viewport.bottom - viewport.top),
                ),
                angleRad = viewport.angleRad,
                pitch = 0f,
                zoom = visibleTiles.level,
                fractionalZoom = fractionalZoom,
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

    /** The tile windows a viewport update carries, kept apart rather than merged. */
    private data class VisibleMatrices(
        val main: TileMatrix,
        val overflow: List<TileMatrix>,
        val leftVisible: Boolean,
        val rightVisible: Boolean,
    )
}

/**
 * How often the layout pass may run. It only fires when the tile set or the integer zoom actually
 * changed, so this is a floor on a rare event rather than the cadence labels move at.
 */
private const val LAYOUT_THROTTLE_MS = 250L

/**
 * How many times a layout pass whose tiles did not all arrive is retried before it gives up and waits
 * for the map to move again. Each retry waits twice as long as the last, starting at
 * [LAYOUT_THROTTLE_MS].
 */
private const val LAYOUT_RETRY_LIMIT = 3

/**
 * How often the placement pass may run -- roughly a frame at 60 Hz. Upstream runs its placement
 * cycle every frame; this is what the split buys, and it is why labels no longer trail the map.
 */
private const val PLACEMENT_THROTTLE_MS = 16L

/**
 * A monotonic millisecond clock for the placement pass.
 *
 * It only ever measures intervals between commits, so its epoch is arbitrary; it is deliberately
 * *not* the draw pass's frame clock, which has an epoch of its own.
 */
private val placementClockStart = TimeSource.Monotonic.markNow()

internal fun nowMillis(): Long = placementClockStart.elapsedNow().inWholeMilliseconds
