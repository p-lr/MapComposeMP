package ovh.plrapps.mapcompose.vector.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import ovh.plrapps.mapcompose.core.TileMatrix
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.utils.AngleRad
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.CompoundLabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.Point
import ovh.plrapps.mapcompose.vector.renderer.Symbol
import ovh.plrapps.mapcompose.vector.renderer.SymbolsProducer
import ovh.plrapps.mapcompose.vector.renderer.TextPlacementCandidate
import ovh.plrapps.mapcompose.vector.renderer.TileRenderer
import ovh.plrapps.mapcompose.vector.renderer.collision.CollisionDetector
import ovh.plrapps.mapcompose.vector.renderer.collision.LabelPlacement
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.utils.LruCache
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint
import pbandk.decodeFromByteArray
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VectorRasterizer(
    val configuration: MapLibreConfiguration,
    val densityState: MutableStateFlow<Density?>,
    val fontFamilyResolverState:  MutableStateFlow<FontFamily.Resolver?>,
    val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    val getTileStream: suspend (url: String, row: Int, col: Int, zoomLvl: Int) -> RawSource?,
    val tileSize: Int = 256,
) {
    // Decoded protobuf Tile objects are large (up to several MB each in dense areas).
    // Keep size modest; raw bytes remain available in byteCache for cheap re-decoding.
    private val tileCache = LruCache<String, Tile>(maxSize = 30)
    private val byteCache = LruCache<String, ByteArray>(maxSize = 100)
    // Rendered tile output (PNG bytes). Cache hit avoids full geometry re-render.
    private val renderedByteCache = LruCache<String, ByteArray>(maxSize = 50)
    private val pathCache = LruCache<String, Any>(maxSize = 200)
    // Separate mutexes per cache eliminate cross-cache contention when tiles render concurrently.
    private val byteCacheMutex = Mutex()
    private val tileCacheMutex = Mutex()
    private val pathCacheMutex = Mutex()
    private val renderedByteCacheMutex = Mutex()

    // Precomputed set of all source names referenced by style layers (constant after init).
    private val allSourceNames: Set<String> by lazy {
        configuration.style.layers.mapNotNull { it.source?.takeIf { s -> s.isNotBlank() } }.toSet()
    }

    // Minimum viewport-pixel distance between repetitions of the same line label across tiles.
    // Mirrors MapLibre's default symbol-spacing (250px).
    private val MIN_LINE_LABEL_REPEAT_DIST = 250f

    private val stableAnchorCache = LruCache<String, Int>(maxSize = 1000)

    private fun getTileKey(sourceName: String, z: Int, x: Int, y: Int): String {
        return "$sourceName-$z-$x-$y"
    }

    fun decodePBFFromByteArray(bytes: ByteArray): Tile? {
        return try {
            Tile.decodeFromByteArray(bytes)
        } catch (e: Exception) {
            println("Error decoding PBF: ${e.message}")
            null
        }
    }

    private suspend fun renderTile(
        pbfList: Map<String, ByteArray>,
        zoom: Double,
        tileSize: Int,
        actualZoom: Double,
        x: Int,
        y: Int,
    ): ImageBitmap {
        val z = zoom.toInt()
        val density = densityState.value ?: return emptyBitmap(tileSize)

        // One tileCache lookup per source (not per style layer) — reduces mutex ops from
        // O(style_layers) to O(sources).
        val tileForSource: Map<String, Tile?> = allSourceNames.associateWith { sourceName ->
            val key = getTileKey(sourceName, z, x, y)
            tileCacheMutex.withLock { tileCache.get(key) }
                ?: pbfList[sourceName]?.let { bytes ->
                    decodePBFFromByteArray(bytes)?.also { t ->
                        tileCacheMutex.withLock { tileCache.put(key, t) }
                    }
                }
        }

        val imageBitmap = ImageBitmap(tileSize, tileSize)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        val localPropCache = HashMap<String, Map<String, Any?>>()
        val tileRenderer = TileRenderer(
            configuration = configuration,
            pathCache = pathCache,
            pathCacheMutex = pathCacheMutex,
            localPropCache = localPropCache
        )

        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(tileSize.toFloat(), tileSize.toFloat())
        ) {
            for (styleLayer in configuration.style.layers) {
                val sourceName = styleLayer.source.takeIf { !it.isNullOrBlank() }
                val tileKey = sourceName?.let { getTileKey(it, z, x, y) }
                val tile = sourceName?.let { tileForSource[it] }

                tileRenderer.render(
                    canvas = this,
                    tile = tile,
                    styleLayer = styleLayer,
                    zoom = zoom,
                    canvasSize = tileSize,
                    actualZoom = actualZoom,
                    tileKey = tileKey
                )
            }
        }
        return imageBitmap
    }

    private suspend fun fetchTile(url: String, row: Int, col: Int, zoomLvl: Int, sourceName: String): Result<ByteArray> {
        val key = getTileKey(sourceName, zoomLvl, col, row)
        byteCacheMutex.withLock {
            byteCache.get(key)?.let { return Result.success(it) }
        }

        try {
            // Check if the coroutine is cancelled before the network request
            currentCoroutineContext().ensureActive()

//            println("fetch the tile $url")
            val result = withContext(IODispatcher) {
                val response = getTileStream(url, row, col, zoomLvl)
                response?.buffered()?.use { bufferedSource ->
                    bufferedSource.readByteArray()
                }
            } ?: return Result.failure(LoadTileException("fetch error"))
            byteCacheMutex.withLock {
                byteCache.put(key, result)
            }
            return Result.success(result)
        } catch (e: CancellationException) {
            // We do not log cancellation as an error - this is normal behavior
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
    }

    // return sourceName: String and tile: ByteArray
    private suspend fun fetch(z: Int, x: Int, y: Int): Result<Map<String, ByteArray>> = supervisorScope {
        try {
            // Kick off concurrent fetches per source without failing the whole scope on one error
            val deferred = configuration.tileSources.map { (sourceName, ts) ->
                sourceName to async {
                    // Ensure still active before heavy work
                    coroutineContext.ensureActive()
                    fetchTile(ts.getTileUrl(z = z, x = x, y = y), row = y, col = x, zoomLvl = z, sourceName = sourceName).getOrThrow()
                }
            }

            // Await all; collect successes, ignore failures so partial data can still render
            val buffer = mutableMapOf<String, ByteArray>()
            for ((sourceName, d) in deferred) {
                runCatching { d.await() }
                    .onSuccess { bytes -> buffer[sourceName] = bytes }
                    .onFailure { /* ignore single source failure */ }
            }

            return@supervisorScope Result.success(buffer)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun emptyBitmap(size: Int): ImageBitmap {
        val density = densityState.value ?: return ImageBitmap(size, size)

        val imageBitmap = ImageBitmap(size, size)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(size.toFloat(), size.toFloat())
        ) {
            this.drawRect(
                color = Color.LightGray,
            )
        }
        return imageBitmap
    }

    suspend fun getTile(
        x: Int,
        y: Int,
        zoom: Double,
        tileSize: Int,
    ): ImageBitmap {
        val z = zoom.toInt()
        val pbfList = fetch(z = z, x = x, y = y).getOrElse { e ->
            println("ERROR: ${e.message}")
            return emptyBitmap(tileSize)
        }

        return renderTile(
            pbfList = pbfList,
            zoom = zoom,
            tileSize = tileSize,
            actualZoom = zoom,
            x = x,
            y = y,
        )
    }

    private val symbolsProducer = SymbolsProducer(
        textMeasurer = textMeasurerState,
        configuration = configuration,
        pathCache = pathCache,
        pathCacheMutex = pathCacheMutex
    )

    suspend fun produceSymbols(viewport: MVTViewport, tileSize: Int, z: Double): Result<List<Symbol>> = withContext(
        Dispatchers.Default
    ) {
        val density = densityState.value ?: return@withContext Result.failure(LoadTileException("density is null"))

        val symbols = mutableListOf<Symbol>()
        if (viewport.tileMatrix.isEmpty()) return@withContext Result.success(symbols)

        val maxTileIndex = (1 shl viewport.zoom.toInt()) - 1
        val visibleRowMin = viewport.tileMatrix.keys.min()
        val visibleRowMax = viewport.tileMatrix.keys.max()

        // Expand by 1 tile in each direction so edge symbols are collision-checked
        // against off-screen content (MapLibre-style viewport padding).
        val expandedTiles = mutableMapOf<Int, IntRange>()
        for ((row, cols) in viewport.tileMatrix) {
            if (cols.isEmpty()) continue
            expandedTiles[row] = (cols.min() - 1).coerceAtLeast(0)..(cols.max() + 1).coerceAtMost(maxTileIndex)
        }
        for (adjRow in listOf(visibleRowMin - 1, visibleRowMax + 1)) {
            if (adjRow < 0 || adjRow > maxTileIndex) continue
            val refRow = if (adjRow < visibleRowMin) visibleRowMin else visibleRowMax
            val refCols = viewport.tileMatrix[refRow] ?: continue
            if (refCols.isEmpty()) continue
            expandedTiles[adjRow] = (refCols.min() - 1).coerceAtLeast(0)..(refCols.max() + 1).coerceAtMost(maxTileIndex)
        }

        for ((y, colRange) in expandedTiles) {
            for (x in colRange) {
                // Loading PBF for the tile
                val pbfList = fetch(z = z.toInt(), x = x, y = y).getOrElse { e ->
                    println("ERROR: ${e.message}")
                    return@withContext Result.failure(e)
                }

                // One tileCache lookup per source (not per style layer).
                val tileForSource: Map<String, Tile?> = allSourceNames.associateWith { sourceName ->
                    val key = getTileKey(sourceName, z.toInt(), x, y)
                    tileCacheMutex.withLock { tileCache.get(key) }
                        ?: pbfList[sourceName]?.let { bytes ->
                            decodePBFFromByteArray(bytes)?.also { t ->
                                tileCacheMutex.withLock { tileCache.put(key, t) }
                            }
                        }
                }

                val localPropCache = HashMap<String, Map<String, Any?>>()

                // Use withIndex() to avoid O(n²) indexOf in the loop.
                for ((layerIndex, styleLayer) in configuration.style.layers.withIndex()) {
                    if (styleLayer !is SymbolLayer) continue
                    val tile = styleLayer.source.takeIf { !it.isNullOrBlank() }
                        ?.let { tileForSource[it] } ?: continue

                    symbolsProducer.produce(
                        tile = tile,
                        styleLayer = styleLayer,
                        layerIndex = layerIndex,
                        zoom = z,
                        canvasSize = tileSize,
                        actualZoom = z,
                        tileX = x,
                        tileY = y,
                        density = density,
                        localPropCache = localPropCache
                    ).let {
                        symbols.addAll(it)
                    }
                }
            }
        }

        Result.success(symbols)
    }

    fun updateSymbols(nextSymbols: List<Symbol>, state: MapState, viewportInfo: ViewportInfo) {
        // run collision detection if enabled
        val symbols = when (this.configuration.collisionDetectionEnabled) {
            true -> clearCollision(nextSymbols, viewportInfo)
            else -> nextSymbols
        }

        checkIndexErrors(symbols)
        println("xxxxx number of symbols ${symbols.size}")
        state.symbolState.symbols = symbols
    }

    private class Counter {
        var value: Int = 0
    }

    private fun checkIndexErrors(nextSymbols: List<Symbol>) {
        val counters = mutableMapOf<String, Counter>()

        nextSymbols.forEach { symbol ->
            counters.getOrPut(symbol.id) { Counter() }.value++
        }
        counters.forEach { (id, value) ->
            if (value.value > 1) {
                println("xxxxx [ERROR]: Id $id not unique (${value.value})")
            }
        }
    }

    /**
     * Creates a LabelPlacement with viewport coordinates.
     *
     * [mapRotationDeg] is added to the OBB rotation only for symbols that rotate with the map
     * (i.e. line text whose own angle is non-zero). Point symbols keep angle = 0 regardless of
     * map rotation because their rotation-alignment defaults to "viewport".
     */
    private fun createViewportLabelPlacement(
        center: Offset,
        originalPlacement: LabelPlacement,
        mapRotationDeg: Float = 0f
    ): LabelPlacement {
        val bounds = originalPlacement.bounds
        val ownAngle = originalPlacement.angle

        // The dimensions remain the same
        val rectWidth = bounds.width
        val rectHeight = bounds.height

        // New bounds relative to viewport center
        val newBounds = Rect(
            left = center.x - rectWidth / 2f,
            top = center.y - rectHeight / 2f,
            right = center.x + rectWidth / 2f,
            bottom = center.y + rectHeight / 2f
        )

        // Line text (non-zero angle) rotates with the map; point symbols stay viewport-aligned.
        val effectiveAngle = if (ownAngle != 0f) ownAngle + mapRotationDeg else ownAngle

        return originalPlacement.copy(
            position = ObbPoint(center.x, center.y),
            bounds = newBounds,
            obb = OBB(
                center = ObbPoint(center.x, center.y),
                size = ovh.plrapps.mapcompose.vector.utils.obb.Size(rectWidth, rectHeight),
                rotation = effectiveAngle
            )
        )
    }

    /**
     * Converts normalized Mercator coordinates to viewport (screen) pixel coordinates,
     * accounting for the current map scale and rotation.
     */
    private fun mercatorToViewport(
        mercatorX: Double,
        mercatorY: Double,
        viewportInfo: ViewportInfo
    ): Offset {
        val centroidX = viewportInfo.centroidX
        val centroidY = viewportInfo.centroidY
        val currentScale = viewportInfo.scale

        var deltaX = mercatorX - centroidX
        if (viewportInfo.infiniteScrollX) {
            if (deltaX > 0.5) deltaX -= 1.0
            else if (deltaX < -0.5) deltaX += 1.0
        }
        val deltaY = mercatorY - centroidY

        val viewportCenterX = viewportInfo.size.width.toDouble() / 2.0
        val viewportCenterY = viewportInfo.size.height.toDouble() / 2.0

        // Scale the map-space delta to screen pixels
        val scaledDX = deltaX * viewportInfo.fullWidth.toDouble() * currentScale
        val scaledDY = deltaY * viewportInfo.fullHeight.toDouble() * currentScale

        // Compose rotate(θ) is clockwise, matching x'=dx*cos(θ)-dy*sin(θ); use +angleRad.
        val angle = viewportInfo.angleRad.toDouble()
        val viewportX = viewportCenterX + scaledDX * cos(angle) - scaledDY * sin(angle)
        val viewportY = viewportCenterY + scaledDX * sin(angle) + scaledDY * cos(angle)

        return Offset(viewportX.toFloat(), viewportY.toFloat())
    }

    /**
     * Sort symbols according to priority
     */
    private fun sortForPlacement(symbols: List<Symbol>): List<Symbol> {
        return symbols.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Symbol>> {
                    it.value.placement.spritePlacement.layerIndex
                }.thenBy {
                    it.value.placement.spritePlacement.inLayerPriority
                }.thenBy {
                    it.index
                }
            )
            .map { it.value }
    }

    private fun makeStableAnchorKey(symbol: Symbol.SpriteWithText): String {
        val text = symbol.textCandidates.firstOrNull()?.labelPlacement?.text ?: ""
        val gx = (symbol.global.x * 100_000.0).toLong()
        val gy = (symbol.global.y * 100_000.0).toLong()
        return "${text}_${gx}_${gy}"
    }

    /**
     * Detects collisions and determines whether the element can be placed or not.
     */
    private fun clearCollision(symbols: List<Symbol>, viewportInfo: ViewportInfo): List<Symbol> {
        val collisionDetector = CollisionDetector()
        val sortedSymbols = sortForPlacement(symbols)
        val acceptedSymbols = mutableListOf<Symbol>()
        val mapRotationDeg = viewportInfo.angleRad * (180f / kotlin.math.PI.toFloat())
        // Cross-tile line-label deduplication: track placed viewport positions per text string.
        val placedLineTextPositions = mutableMapOf<String, MutableList<Offset>>()

        sortedSymbols.forEach { symbol ->
            val viewportPos = mercatorToViewport(
                mercatorX = symbol.global.x,
                mercatorY = symbol.global.y,
                viewportInfo = viewportInfo
            )

            val spritePlacement = symbol.placement.spritePlacement
            val textPlacement = symbol.placement.textPlacement

            when (symbol) {
                is Symbol.SpriteWithText -> {
                    val spriteViewportPlacement = createViewportLabelPlacement(
                        center = viewportPos,
                        originalPlacement = spritePlacement,
                        mapRotationDeg = mapRotationDeg
                    )

                    if (symbol.textCandidates.isNotEmpty()) {
                        // Candidate path: try each text position in order, pick the first that fits.
                        // Used for both text-variable-anchor (multiple candidates) and text-anchor
                        // (single candidate) — textCandidates is always non-empty for SpriteWithText.
                        if (!collisionDetector.wouldCollide(spriteViewportPlacement)) {
                            var placed = false
                            val stableKey = makeStableAnchorKey(symbol)
                            val lastIndex = stableAnchorCache.get(stableKey)
                            val orderedCandidates: List<IndexedValue<TextPlacementCandidate>> =
                                if (lastIndex != null && lastIndex < symbol.textCandidates.size) {
                                    listOf(IndexedValue(lastIndex, symbol.textCandidates[lastIndex])) +
                                        symbol.textCandidates.withIndex().filter { it.index != lastIndex }
                                } else {
                                    symbol.textCandidates.withIndex().toList()
                                }
                            for ((index, candidate) in orderedCandidates) {
                                val textVP = mercatorToViewport(candidate.mercatorX, candidate.mercatorY, viewportInfo)
                                val textVPPlacement = createViewportLabelPlacement(textVP, candidate.labelPlacement, mapRotationDeg)
                                if (!collisionDetector.wouldCollide(textVPPlacement)) {
                                    collisionDetector.insert(spriteViewportPlacement)
                                    collisionDetector.insert(textVPPlacement)
                                    stableAnchorCache.put(stableKey, index)
                                    // Text added first: SymbolComposer draws reversed(), so first
                                    // entries are drawn on top — text should render above sprite.
                                    acceptedSymbols.add(
                                        Symbol.Text(
                                            id = "${symbol.id}_t",
                                            global = Point(candidate.mercatorX, candidate.mercatorY),
                                            placement = CompoundLabelPlacement(candidate.labelPlacement, candidate.labelPlacement),
                                            value = symbol.text,
                                            spriteAnchorGlobal = symbol.global,
                                            textOffset = Pair(candidate.dx, candidate.dy),
                                        )
                                    )
                                    acceptedSymbols.add(
                                        Symbol.Sprite(
                                            id = "${symbol.id}_s",
                                            global = symbol.global,
                                            placement = CompoundLabelPlacement(spritePlacement, null),
                                            value = symbol.sprite,
                                        )
                                    )
                                    placed = true
                                    break
                                }
                            }
                            if (!placed && symbol.textOptional) {
                                collisionDetector.insert(spriteViewportPlacement)
                                acceptedSymbols.add(
                                    Symbol.Sprite(
                                        id = symbol.id,
                                        global = symbol.global,
                                        placement = CompoundLabelPlacement(spritePlacement, null),
                                        value = symbol.sprite,
                                    )
                                )
                            }
                        } else if (symbol.iconOptional) {
                            // Sprite collides but icon is optional — try text-only placement.
                            for (candidate in symbol.textCandidates) {
                                val textVP = mercatorToViewport(candidate.mercatorX, candidate.mercatorY, viewportInfo)
                                val textVPPlacement = createViewportLabelPlacement(textVP, candidate.labelPlacement, mapRotationDeg)
                                if (!collisionDetector.wouldCollide(textVPPlacement)) {
                                    collisionDetector.insert(textVPPlacement)
                                    acceptedSymbols.add(
                                        Symbol.Text(
                                            id = "${symbol.id}_t",
                                            global = Point(candidate.mercatorX, candidate.mercatorY),
                                            placement = CompoundLabelPlacement(candidate.labelPlacement, candidate.labelPlacement),
                                            value = symbol.text,
                                        )
                                    )
                                    break
                                }
                            }
                        }
                    } else {
                        val textViewportPlacement = textPlacement?.let { textPlace ->
                            val spriteHeight = symbol.spriteSize.height.toFloat()
                            val verticalGap = symbol.verticalGap
                            val textHeight = symbol.textSize.height.toFloat()

                            val textOffsetY = spriteHeight / 2f + verticalGap + textHeight / 2f
                            // SpriteWithText is viewport-aligned: text is always directly below the
                            // sprite in screen space, regardless of map rotation.
                            val textViewportPos = Offset(viewportPos.x, viewportPos.y + textOffsetY)

                            createViewportLabelPlacement(
                                center = textViewportPos,
                                originalPlacement = textPlace,
                                mapRotationDeg = mapRotationDeg
                            )
                        }

                        val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)
                        val textCanPlace = textViewportPlacement?.let {
                            !collisionDetector.wouldCollide(it)
                        } ?: true

                        if (spriteCanPlace && textCanPlace) {
                            // Use insert() (not tryPlaceLabel) because we already validated both parts
                            // above. tryPlaceLabel would re-check after sprite is inserted and reject
                            // the text OBB since padded sprite/text boxes always overlap each other.
                            collisionDetector.insert(spriteViewportPlacement)
                            textViewportPlacement?.let { collisionDetector.insert(it) }
                            acceptedSymbols.add(symbol)
                        } else if (spriteCanPlace && !textCanPlace && symbol.textOptional) {
                            collisionDetector.insert(spriteViewportPlacement)
                            acceptedSymbols.add(
                                Symbol.Sprite(
                                    id = symbol.id,
                                    global = symbol.global,
                                    placement = CompoundLabelPlacement(spritePlacement, spritePlacement),
                                    value = symbol.sprite
                                )
                            )
                        } else if (!spriteCanPlace && textCanPlace && symbol.iconOptional && textPlacement != null && textViewportPlacement != null) {
                            collisionDetector.insert(textViewportPlacement)
                            acceptedSymbols.add(
                                Symbol.Text(
                                    id = symbol.id,
                                    global = symbol.global,
                                    placement = CompoundLabelPlacement(textPlacement, textPlacement),
                                    value = symbol.text
                                )
                            )
                        }
                    }
                }

                is Symbol.Sprite -> {
                    val spriteViewportPlacement = createViewportLabelPlacement(
                        center = viewportPos,
                        originalPlacement = spritePlacement,
                        mapRotationDeg = mapRotationDeg
                    )

                    val textViewportPlacement = textPlacement?.let { textPlace ->
                        createViewportLabelPlacement(
                            center = viewportPos,
                            originalPlacement = textPlace,
                            mapRotationDeg = mapRotationDeg
                        )
                    }

                    val spriteCanPlace = !collisionDetector.wouldCollide(spriteViewportPlacement)
                    val textCanPlace = textViewportPlacement?.let {
                        !collisionDetector.wouldCollide(it)
                    } ?: true

                    if (spriteCanPlace && textCanPlace) {
                        collisionDetector.insert(spriteViewportPlacement)
                        textViewportPlacement?.let { collisionDetector.insert(it) }
                        acceptedSymbols.add(symbol)
                    }
                }

                is Symbol.Text -> {
                    // textPlacement is always non-null for Symbol.Text (set in producePointText/produceLineText)
                    val resolvedTextPlacement = textPlacement ?: return@forEach
                    val textViewportPlacement = createViewportLabelPlacement(
                        center = viewportPos,
                        originalPlacement = resolvedTextPlacement,
                        mapRotationDeg = mapRotationDeg
                    )

                    // Cross-tile deduplication for line labels: suppress if a same-text label was
                    // already placed within MIN_LINE_LABEL_REPEAT_DIST viewport pixels. Point labels
                    // (viewportAligned = true) rely on collision detection instead.
                    if (!symbol.viewportAligned) {
                        val text = resolvedTextPlacement.text
                        val prev = placedLineTextPositions[text]
                        if (prev != null && prev.any { placed ->
                                val dx = viewportPos.x - placed.x
                                val dy = viewportPos.y - placed.y
                                sqrt(dx * dx + dy * dy) < MIN_LINE_LABEL_REPEAT_DIST
                            }) {
                            return@forEach
                        }
                    }

                    if (!collisionDetector.wouldCollide(textViewportPlacement)) {
                        collisionDetector.insert(textViewportPlacement)
                        acceptedSymbols.add(symbol)
                        if (!symbol.viewportAligned) {
                            placedLineTextPositions
                                .getOrPut(resolvedTextPlacement.text) { mutableListOf() }
                                .add(viewportPos)
                        }
                    }
                }
            }
        }

        return acceptedSymbols
    }
}

/**
 * Provides information about the current state of the viewport in a MapLibre-like map renderer.
 *
 * @property matrix The current tile transformation matrix, describing how map tiles are projected and positioned in the viewport.
 * @property size The pixel size (width and height) of the viewport.
 * @property angleRad The rotation angle of the viewport, in radians.
 * @property pitch The pitch (tilt) of the viewport, in degrees (0 = looking straight down).
 * @property zoom The current zoom level, providing continuous zoom information.
 */
data class ViewportInfo(
    val matrix: TileMatrix,
    val size: IntSize,
    val angleRad: AngleRad,
    val pitch: Float,
    val zoom: Int,

    // Snapshot values
    val centroidX: Double,
    val centroidY: Double,
    val scale: Double,
    val fullWidth: Int,
    val fullHeight: Int,
    val infiniteScrollX: Boolean = false,
    val visiblePhases: IntRange = 0..0,
)

class LoadTileException(msg: String) : Exception(msg)