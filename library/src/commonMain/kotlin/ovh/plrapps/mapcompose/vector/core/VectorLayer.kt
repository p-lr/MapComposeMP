package ovh.plrapps.mapcompose.vector.core

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import ovh.plrapps.mapcompose.core.Viewport
import ovh.plrapps.mapcompose.core.VisibleTiles
import ovh.plrapps.mapcompose.core.VisibleWindow
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.utils.throttle
import ovh.plrapps.mapcompose.vector.data.extension.toBytes
import ovh.plrapps.mapcompose.vector.data.extension.toMVTViewport
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration

internal class VectorLayer(
    private val mapState: MapState,
    private val vectorTileStreamProvider: VectorTileStreamProvider,
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
            val tilePx = with(density) { 256.dp.toPx() }.toInt()

            val imageBitmap = rasterizer.getTile(
                x = col,
                y = row,
                zoom = zoomLvl.toDouble(),
                tileSize = tilePx
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
                    val tilePx = mapState.tileSize

                    val nextSymbols = rasterizer.produceSymbols(
                        viewport = viewportInfo.toMVTViewport(),
                        tileSize = tilePx,
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