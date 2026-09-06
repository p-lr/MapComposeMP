package ovh.plrapps.mapcompose.vector.core

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.IntOffset
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
import ovh.plrapps.mapcompose.utils.AngleRad
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.DemUnpack
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.SourceType
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.byteArrayToImageBitmap
import ovh.plrapps.mapcompose.vector.renderer.DemTile
import ovh.plrapps.mapcompose.vector.renderer.NeighbourTile
import ovh.plrapps.mapcompose.vector.renderer.RasterTileImage
import ovh.plrapps.mapcompose.vector.renderer.TileRenderer
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.symbol.CrossTileSymbolIndex
import ovh.plrapps.mapcompose.vector.symbol.Placement
import ovh.plrapps.mapcompose.vector.symbol.PlacementOrder
import ovh.plrapps.mapcompose.vector.symbol.SymbolBucket
import ovh.plrapps.mapcompose.vector.symbol.SymbolBucketBuilder
import ovh.plrapps.mapcompose.vector.symbol.SYMBOL_FADE_DURATION_MS
import ovh.plrapps.mapcompose.vector.utils.LruCache
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonSource
import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.HeatmapLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.SymbolLayer
import pbandk.decodeFromByteArray
import kotlin.collections.component1
import kotlin.collections.component2

class VectorRasterizer(
    val configuration: MapLibreConfiguration,
    val densityState: MutableStateFlow<Density?>,
    val fontFamilyResolverState:  MutableStateFlow<FontFamily.Resolver?>,
    val textMeasurerState: MutableStateFlow<TextMeasurer?>,
    val getTileStream: suspend (url: String, row: Int, col: Int, zoomLvl: Int) -> RawSource?,
) {
    // Decoded protobuf Tile objects are large (up to several MB each in dense areas).
    // Keep size modest; raw bytes remain available in byteCache for cheap re-decoding.
    private val tileCache = LruCache<String, Tile>(maxSize = 30)
    /* Encoded tile bytes. Sized well above the decoded-tile cache on purpose: a `tileCache` miss
     * must cost a re-decode and not a network round trip, because a fetch that fails is what makes
     * `layoutBuckets` unable to publish a bucket for a tile that is on screen. */
    private val byteCache = LruCache<String, ByteArray>(maxSize = 256)
    private val pathCache = LruCache<String, Any>(maxSize = PATH_CACHE_SIZE)
    // Separate mutexes per cache eliminate cross-cache contention when tiles render concurrently.
    private val byteCacheMutex = Mutex()
    private val tileCacheMutex = Mutex()
    private val pathCacheMutex = Mutex()

    // Decoded raster tiles. Small: an image tile is megabytes once decoded, and the encoded bytes
    // stay in byteCache for a cheap re-decode.
    private val rasterImageCache = LruCache<String, ImageBitmap>(maxSize = 30)
    private val rasterImageCacheMutex = Mutex()

    /* Tiles cut out of a geojson document. `GeoJsonTiler.tile` clips and simplifies every feature
     * of the document per call, and nothing else memoizes it -- which the neighbour gathering a
     * `circle` layer needs would multiply by nine, over tiles the map is drawing anyway. */
    private val geoJsonTileCache = LruCache<String, Tile>(maxSize = 30)
    private val geoJsonTileCacheMutex = Mutex()

    // Decoded elevation tiles, keyed by the tile actually fetched. Building one costs its 8
    // neighbours' bytes too (see buildDem), so this cache is what keeps the border backfill to
    // roughly one extra fetch per DEM tile rather than nine per map tile.
    private val demCache = LruCache<String, DemData>(maxSize = 30)
    private val demCacheMutex = Mutex()

    // Precomputed source names referenced by style layers (constant after init), split by what the
    // source serves: neither a raster nor a raster-dem source may reach the protobuf decoder, a
    // vector source must never reach the image decoder, and only a raster-dem source's channels
    // mean elevation.
    private val referencedSourceNames: Set<String> by lazy {
        configuration.style.layers.mapNotNull { it.source?.takeIf { s -> s.isNotBlank() } }.toSet()
    }

    /* A whitelist, not "everything that is not raster": a raster-dem source's bytes are a PNG too,
     * and feeding one to the protobuf decoder only produces noise. */
    private val vectorSourceNames: Set<String> by lazy {
        referencedSourceNames.filterTo(mutableSetOf()) { typeOf(it) == SourceType.VECTOR }
    }

    private val rasterSourceNames: Set<String> by lazy {
        referencedSourceNames.filterTo(mutableSetOf()) { typeOf(it) == SourceType.RASTER }
    }

    private val demSourceNames: Set<String> by lazy {
        referencedSourceNames.filterTo(mutableSetOf()) { typeOf(it) == SourceType.RASTER_DEM }
    }

    /* The vector sources a heatmap layer reads. A heatmap kernel reaches past the tile its point
     * belongs to, so those sources' 8 neighbouring tiles are fetched as well and handed to the
     * painter -- the border treatment `buildDem` needs for hillshade, for the same reason: without
     * it a hot cluster near a tile edge cools off at the seam. */
    private val heatmapSourceNames: Set<String> by lazy {
        configuration.style.layers.filterIsInstance<HeatmapLayer>()
            .mapNotNull { it.source?.takeIf { s -> s.isNotBlank() } }
            .filterTo(mutableSetOf()) { typeOf(it) == SourceType.VECTOR }
    }

    /* The sources a `circle` layer reads -- vector and geojson alike, unlike the heatmap's set,
     * because a geojson document is cut into tiles here and its tiles have edges too. A disc
     * straddling a tile boundary is drawn by both tiles, so the neighbouring tiles are gathered for
     * these sources as well; see `renderer/utils/CircleVertexGate.kt`. */
    private val circleSourceNames: Set<String> by lazy {
        configuration.style.layers.filterIsInstance<CircleLayer>()
            .mapNotNull { it.source?.takeIf { s -> s.isNotBlank() } }
            .filterTo(mutableSetOf()) {
                typeOf(it) == SourceType.VECTOR || configuration.geoJsonSources.containsKey(it)
            }
    }

    /** The `geojson` sources any layer reads; each is cut from a document held in memory. */
    private val geoJsonSourceNames: Set<String> by lazy {
        referencedSourceNames.filterTo(mutableSetOf()) { configuration.geoJsonSources.containsKey(it) }
    }

    private fun typeOf(sourceName: String): SourceType =
        configuration.tileSources[sourceName]?.type ?: SourceType.VECTOR

    /** The source types a tile bitmap is drawn from. Every other type is never fetched. */
    private val TILE_SOURCE_TYPES =
        setOf(SourceType.VECTOR, SourceType.RASTER, SourceType.RASTER_DEM)

    private fun getTileKey(sourceName: String, z: Int, x: Int, y: Int): String {
        return "$sourceName-$z-$x-$y"
    }

    /** The key of the tile [ref] names -- the tile actually fetched, not the one requested. */
    private fun getTileKey(sourceName: String, ref: TileRef): String =
        getTileKey(sourceName, ref.z, ref.x, ref.y)

    /**
     * The key a *rendered* tile's per-feature work is cached under.
     *
     * It names the fetched tile rather than the requested one, so the `span x span` map tiles of an
     * overzoomed source share one decode, one evaluated-property cache and -- because the geometry
     * is decoded in the ancestor's space -- one built [androidx.compose.ui.graphics.Path]. The span
     * belongs in the key because it is what that space is scaled by.
     */
    private fun getRenderKey(sourceName: String, ref: TileRef): String =
        "$sourceName-${ref.z}-${ref.x}-${ref.y}-s${ref.span}"

    /**
     * Which tile of every source covers the map tile at [z]/[x]/[y], and which part of it.
     *
     * A source that does not reach [z] -- below its `minzoom` -- is absent from the result and draws
     * nothing, as upstream's `covering_tiles.ts` returns no tiles for it. Above its `maxzoom` the
     * ref names an ancestor and the sub-square this map tile is; see [resolveOverscaled].
     */
    private fun resolveRefs(z: Int, x: Int, y: Int): Map<String, TileRef> = buildMap {
        for ((name, source) in configuration.tileSources) {
            source.resolve(z = z, x = x, y = y)?.let { put(name, it) }
        }
        for ((name, source) in configuration.geoJsonSources) {
            source.resolve(z = z, x = x, y = y)?.let { put(name, it) }
        }
    }

    fun decodePBFFromByteArray(bytes: ByteArray): Tile? {
        return try {
            Tile.decodeFromByteArray(bytes)
        } catch (e: Exception) {
            println("Error decoding PBF: ${e.message}")
            null
        }
    }

    /**
     * Decodes a raster source's tile.
     *
     * [byteArrayToImageBitmap] throws on Skia and NPEs on Android when the bytes are not a decodable
     * image, so this swallows both the way [decodePBFFromByteArray] does: one bad tile should leave
     * a hole, not fail the whole rasterization.
     */
    fun decodeImageFromByteArray(bytes: ByteArray): ImageBitmap? {
        return try {
            byteArrayToImageBitmap(bytes)
        } catch (e: Exception) {
            println("Error decoding raster tile: ${e.message}")
            null
        }
    }

    private suspend fun renderTile(
        fetched: Map<String, FetchedTile>,
        refs: Map<String, TileRef>,
        zoom: Double,
        tileSize: Int,
        actualZoom: Double,
        x: Int,
        y: Int,
        superSampling: Int = 1,
    ): ImageBitmap {
        val z = zoom.toInt()
        val screenDensity = densityState.value ?: return emptyBitmap(tileSize)
        /* Every style width a painter draws goes through `canvas.density`, and the whole tile is
         * rasterized [superSampling] times larger than it will be handed on at. Leaving the draw
         * scope at the screen density would therefore divide every `line-width`, `circle-radius` and
         * `*-translate` by the super-sampling factor once the bitmap is filtered back down --
         * super-sampling would thin the map instead of just smoothing it. */
        val density = if (superSampling > 1) {
            Density(screenDensity.density * superSampling, screenDensity.fontScale)
        } else {
            screenDensity
        }

        // One tileCache lookup per source (not per style layer) — reduces mutex ops from
        // O(style_layers) to O(sources).
        /* A geojson source is not fetched: its tile is cut out of the loaded document here, and
         * from `TileRenderer`'s point of view it is an ordinary vector tile from then on. */
        val geoJsonForSource: Map<String, Tile?> = geoJsonSourceNames.associateWith { sourceName ->
            val ref = refs[sourceName] ?: return@associateWith null
            geoJsonTile(sourceName, ref)
        }

        /* Keyed by the tile actually fetched, like the raster and DEM caches below: an overzoomed
         * source's sibling map tiles share one ancestor and must share its single decode. */
        val tileForSource: Map<String, Tile?> = vectorSourceNames.associateWith { sourceName ->
            val ref = refs[sourceName] ?: return@associateWith null
            val key = getTileKey(sourceName, ref)
            tileCacheMutex.withLock { tileCache.get(key) }
                ?: fetched[sourceName]?.let { tile ->
                    decodePBFFromByteArray(tile.bytes)?.also { t ->
                        tileCacheMutex.withLock { tileCache.put(key, t) }
                    }
                }
        }

        /* Raster sources are keyed by the tile actually fetched, not by the tile requested: when the
         * source is overzoomed several map tiles share one ancestor image and differ only in which
         * part of it they crop. */
        val rasterForSource: Map<String, RasterTileImage?> = rasterSourceNames.associateWith { sourceName ->
            val tile = fetched[sourceName] ?: return@associateWith null
            val key = getTileKey(sourceName, tile.ref.z, tile.ref.x, tile.ref.y)
            val image = rasterImageCacheMutex.withLock { rasterImageCache.get(key) }
                ?: decodeImageFromByteArray(tile.bytes)?.also { decoded ->
                    rasterImageCacheMutex.withLock { rasterImageCache.put(key, decoded) }
                }
            image?.let { RasterTileImage.of(it, tile.ref) }
        }

        val demForSource: Map<String, DemTile?> = demSourceNames.associateWith { sourceName ->
            val tile = fetched[sourceName] ?: return@associateWith null
            buildDem(sourceName, tile.ref)?.let { DemTile(dem = it, ref = tile.ref) }
        }

        val localPropCache = HashMap<String, EvalFeature>()
        val tileRenderer = TileRenderer(
            configuration = configuration,
            pathCache = pathCache,
            pathCacheMutex = pathCacheMutex,
            localPropCache = localPropCache
        )

        /* Gated on a layer that needs them actually drawing at this zoom, because the neighbours
         * cost 8 fetches per source -- a heatmap is commonly bounded to a narrow zoom range, and a
         * style with no circle layer must not pay for one. A source read by both gathers once. */
        fun needsNeighbours(predicate: (Layer) -> Boolean): Boolean =
            configuration.style.layers.any {
                predicate(it) && tileRenderer.isLayerVisible(it) && tileRenderer.isZoomInRange(it, zoom)
            }

        val neighbourSourceNames = buildSet {
            if (heatmapSourceNames.isNotEmpty() && needsNeighbours { it is HeatmapLayer }) {
                addAll(heatmapSourceNames)
            }
            if (circleSourceNames.isNotEmpty() && needsNeighbours { it is CircleLayer }) {
                addAll(circleSourceNames)
            }
        }

        val neighboursForSource: Map<String, List<NeighbourTile>> =
            neighbourSourceNames.associateWith { sourceName ->
                val ref = refs[sourceName] ?: return@associateWith emptyList()
                if (sourceName in geoJsonSourceNames) {
                    neighbourGeoJsonTiles(sourceName, ref)
                } else {
                    neighbourVectorTiles(sourceName, ref)
                }
            }

        val imageBitmap = ImageBitmap(tileSize, tileSize)
        val canvas = Canvas(imageBitmap)
        val drawScope = CanvasDrawScope()

        drawScope.draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(tileSize.toFloat(), tileSize.toFloat())
        ) {
            for (styleLayer in configuration.style.layers) {
                val sourceName = styleLayer.source.takeIf { !it.isNullOrBlank() }
                val ref = sourceName?.let { refs[it] } ?: TileRef.whole(z = z, x = x, y = y)
                val tileKey = sourceName?.let { getRenderKey(it, ref) }
                val tile = sourceName?.let { tileForSource[it] ?: geoJsonForSource[it] }

                tileRenderer.render(
                    canvas = this,
                    tile = tile,
                    styleLayer = styleLayer,
                    zoom = zoom,
                    canvasSize = tileSize,
                    actualZoom = actualZoom,
                    tileKey = tileKey,
                    rasterImage = sourceName?.let { rasterForSource[it] },
                    demTile = sourceName?.let { demForSource[it] },
                    tileY = y,
                    tileX = x,
                    neighbours = sourceName
                        ?.let { neighboursForSource[it] }
                        ?: emptyList(),
                    tileRef = ref,
                )
            }
        }
        return imageBitmap
    }

    /**
     * The 8 vector tiles around [z]/[x]/[y] of [sourceName], decoded.
     *
     * A heatmap layer and a circle layer need these, because a kernel and a disc both cross tile
     * boundaries -- see [ovh.plrapps.mapcompose.vector.renderer.HeatmapLayerPainter] and
     * [ovh.plrapps.mapcompose.vector.renderer.utils.CircleVertexGate]. A neighbour that fails to
     * fetch is simply left out, which costs the tile whatever that neighbour held -- its heat, or
     * the half of a disc it owns, which the MVT buffer's copy then draws instead -- rather than the
     * whole layer.
     */
    private suspend fun neighbourVectorTiles(
        sourceName: String,
        centre: TileRef,
    ): List<NeighbourTile> {
        return supervisorScope {
            neighbourRefs(centre)
                .map { (offset, ref) -> offset to async { decodeVectorTile(sourceName, ref) } }
                .mapNotNull { (offset, deferred) ->
                    val tile = runCatching { deferred.await() }.getOrNull() ?: return@mapNotNull null
                    val (dx, dy) = offset
                    NeighbourTile(tile = tile, dx = dx, dy = dy)
                }
        }
    }

    /**
     * The 8 geojson tiles around [centre], cut from the document in memory.
     *
     * The geojson counterpart of [neighbourVectorTiles]: no network, but each cut walks the whole
     * document, hence [geoJsonTile]'s cache.
     */
    private suspend fun neighbourGeoJsonTiles(
        sourceName: String,
        centre: TileRef,
    ): List<NeighbourTile> {
        val source = configuration.geoJsonSources[sourceName] ?: return emptyList()
        return neighbourRefs(centre).mapNotNull { (offset, ref) ->
            val tile = geoJsonTile(sourceName, ref, source) ?: return@mapNotNull null
            val (dx, dy) = offset
            NeighbourTile(tile = tile, dx = dx, dy = dy)
        }
    }

    /**
     * One tile cut out of a geojson document, memoized by the tile it names.
     *
     * Suspending because of the cache: an `LruCache` is not thread-safe and its `get` is a write --
     * it reinserts the entry to record recency -- while several tiles rasterize concurrently.
     */
    private suspend fun geoJsonTile(
        sourceName: String,
        ref: TileRef,
        source: GeoJsonSource? = configuration.geoJsonSources[sourceName],
    ): Tile? {
        source ?: return null
        val key = getTileKey(sourceName, ref)
        geoJsonTileCacheMutex.withLock { geoJsonTileCache.get(key) }?.let { return it }
        return source.tile(ref)?.also {
            geoJsonTileCacheMutex.withLock { geoJsonTileCache.put(key, it) }
        }
    }

    /** Fetches and decodes one vector tile, going through both caches the render path uses. */
    private suspend fun decodeVectorTile(sourceName: String, ref: TileRef): Tile? {
        val key = getTileKey(sourceName, ref.z, ref.x, ref.y)
        tileCacheMutex.withLock { tileCache.get(key) }?.let { return it }

        val source = configuration.tileSources[sourceName] ?: return null
        val bytes = fetchTile(source.getTileUrl(ref), ref = ref, sourceName = sourceName)
            .getOrNull() ?: return null
        return decodePBFFromByteArray(bytes)?.also { tile ->
            tileCacheMutex.withLock { tileCache.put(key, tile) }
        }
    }

    /**
     * Decodes the elevation tile [ref] of [sourceName], with its border ring backfilled.
     *
     * The [DemData] covers the *whole* fetched tile, never the sub-square an overzoomed map tile
     * crops: that is what lets several map tiles share one decode, and it keeps the border ring
     * reachable from every sub-square. Borders come from the 8 neighbouring tiles, as upstream's
     * `DEMData#backfillBorder` does -- without them the Sobel operator at a tile edge is halved and
     * a grid of seams shows across the map. A neighbour that fails to fetch simply leaves the
     * clamp-to-edge seed in place.
     */
    private suspend fun buildDem(sourceName: String, ref: TileRef): DemData? {
        val key = getTileKey(sourceName, ref.z, ref.x, ref.y)
        demCacheMutex.withLock { demCache.get(key) }?.let { return it }

        val source = configuration.tileSources[sourceName] ?: return null
        val unpack = source.demUnpack ?: return null

        val dem = decodeDem(sourceName, ref, unpack) ?: return null

        val neighbours = supervisorScope {
            neighbourRefs(ref)
                .map { (offset, neighbourRef) ->
                    offset to async { decodeDem(sourceName, neighbourRef, unpack) }
                }
                .map { (offset, deferred) ->
                    offset to runCatching { deferred.await() }.getOrNull()
                }
        }

        for ((offset, neighbour) in neighbours) {
            val (dx, dy) = offset
            neighbour ?: continue
            /* A neighbour of a different size cannot be stitched onto this one; upstream throws,
             * but here the clamped seed is a better outcome than dropping the tile. */
            if (neighbour.dim != dem.dim) continue
            dem.backfillBorder(neighbour, dx, dy)
        }

        demCacheMutex.withLock { demCache.put(key, dem) }
        return dem
    }

    /** Fetches and decodes one elevation tile, without touching its own border. */
    private suspend fun decodeDem(sourceName: String, ref: TileRef, unpack: DemUnpack): DemData? {
        val source = configuration.tileSources[sourceName] ?: return null
        val bytes = fetchTile(source.getTileUrl(ref), ref = ref, sourceName = sourceName)
            .getOrNull() ?: return null
        val image = decodeImageFromByteArray(bytes) ?: return null
        return DemData.ofImage(image, unpack)
    }

    /**
     * The 8 tiles around [ref], paired with their offset.
     *
     * Wraps in x and drops out-of-range rows in y, as upstream's
     * `RasterDEMTileSource#_getNeighboringTiles` does -- the map is cyclic east-west but not
     * north-south.
     */
    private fun neighbourRefs(ref: TileRef): List<Pair<Pair<Int, Int>, TileRef>> {
        val tiles = 1 shl ref.z
        val result = mutableListOf<Pair<Pair<Int, Int>, TileRef>>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val ny = ref.y + dy
                if (ny < 0 || ny >= tiles) continue
                val nx = ((ref.x + dx) % tiles + tiles) % tiles
                result.add((dx to dy) to TileRef.whole(z = ref.z, x = nx, y = ny))
            }
        }
        return result
    }

    private suspend fun fetchTile(url: String, ref: TileRef, sourceName: String): Result<ByteArray> {
        /* Keyed by the tile actually fetched, so overzoomed map tiles sharing one ancestor image
         * share one cache entry rather than re-fetching it per sub-square. */
        val key = getTileKey(sourceName, ref.z, ref.x, ref.y)
        byteCacheMutex.withLock {
            byteCache.get(key)?.let { return Result.success(it) }
        }

        try {
            // Check if the coroutine is cancelled before the network request
            currentCoroutineContext().ensureActive()

//            println("fetch the tile $url")
            val result = withContext(IODispatcher) {
                val response = getTileStream(url, ref.y, ref.x, ref.z)
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

    /** A source's tile bytes, and which tile of that source they are. */
    private class FetchedTile(val bytes: ByteArray, val ref: TileRef)

    /**
     * Fetches the tile each of [refs] names, for every source of one of [types], concurrently.
     *
     * Every source type overzooms, which is upstream: `covering_tiles.ts` clamps the requested zoom
     * to the source's `maxzoom` for the canonical tile coordinates whatever the source serves. What
     * differs is what is done with the ancestor. An image source is *stretched* -- upstream's
     * `reparseOverscaled: false`, here a crop plus a scaled `drawImage`. A vector or geojson source
     * is *re-parsed at the display zoom* -- `reparseOverscaled: true` -- which here means the
     * ancestor's geometry is decoded at `canvasSize * span` while filters and paint properties keep
     * seeing the requested zoom. See [TileRenderer].
     */
    private suspend fun fetch(
        refs: Map<String, TileRef>,
        types: Set<SourceType>,
    ): Result<Map<String, FetchedTile>> = supervisorScope {
        try {
            // Kick off concurrent fetches per source without failing the whole scope on one error
            val deferred = configuration.tileSources.mapNotNull { (sourceName, ts) ->
                if (ts.type !in types) return@mapNotNull null
                val ref = refs[sourceName] ?: return@mapNotNull null
                sourceName to async {
                    // Ensure still active before heavy work
                    coroutineContext.ensureActive()
                    FetchedTile(
                        bytes = fetchTile(ts.getTileUrl(ref), ref = ref, sourceName = sourceName).getOrThrow(),
                        ref = ref,
                    )
                }
            }

            // Await all; collect successes, ignore failures so partial data can still render
            val buffer = mutableMapOf<String, FetchedTile>()
            for ((sourceName, d) in deferred) {
                runCatching { d.await() }
                    .onSuccess { tile -> buffer[sourceName] = tile }
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

    /**
     * Rasterizes one tile into a [tileSize] x [tileSize] bitmap.
     *
     * With [superSampling] > 1 the tile is drawn that many times larger and filtered back down to
     * [tileSize] before returning, so the caller always gets the size it asked for. That trades
     * `superSampling²` fill rate for cleaner hairlines and text: the tile is minified again when it
     * is drawn (a tile covers `tileSize * relativeScale` device pixels, `relativeScale` in
     * `(0.5, 1.0]`), and that second minification is a plain bilinear sample.
     */
    suspend fun getTile(
        x: Int,
        y: Int,
        zoom: Double,
        tileSize: Int,
        superSampling: Int = 1,
    ): ImageBitmap {
        val z = zoom.toInt()
        val refs = resolveRefs(z = z, x = x, y = y)
        val fetched = fetch(refs = refs, types = TILE_SOURCE_TYPES).getOrElse { e ->
            println("ERROR: ${e.message}")
            return emptyBitmap(tileSize)
        }

        val rendered = renderTile(
            fetched = fetched,
            refs = refs,
            zoom = zoom,
            tileSize = tileSize,
            actualZoom = zoom,
            x = x,
            y = y,
            superSampling = superSampling,
        )

        return if (superSampling > 1) downsample(rendered, tileSize / superSampling) else rendered
    }

    /**
     * Filters [source] down to [targetSize], with the bicubic sampling of [FilterQuality.High] —
     * the point of super-sampling is the quality of this step, so it does not use the default.
     */
    private fun downsample(source: ImageBitmap, targetSize: Int): ImageBitmap {
        val density = densityState.value ?: return source
        if (targetSize <= 0) return source

        val target = ImageBitmap(targetSize, targetSize)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(target),
            size = Size(targetSize.toFloat(), targetSize.toFloat()),
        ) {
            drawImage(
                image = source,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(source.width, source.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(targetSize, targetSize),
                filterQuality = FilterQuality.High,
            )
        }
        return target
    }

    private val symbolBucketBuilder = SymbolBucketBuilder(
        textMeasurer = textMeasurerState,
        configuration = configuration,
        pathCache = pathCache,
        pathCacheMutex = pathCacheMutex
    )

    /**
     * Laid-out buckets, keyed by the tile *fetched* and the style layer.
     *
     * This cache is the point of splitting layout from placement. A bucket is a function of
     * (canonical tile, style layer, integer zoom) alone, so panning and rotating -- and zooming
     * within one level -- reuse it, and only [Placement] runs again.
     */
    private val symbolBucketCache = LruCache<String, SymbolBucket>(maxSize = SYMBOL_BUCKET_CACHE_SIZE)
    private val symbolBucketCacheMutex = Mutex()

    /**
     * What the last layout pass published, keyed as [symbolBucketCache] is.
     *
     * Not a second cache: it is one pass's working set, replaced wholesale by the next pass, and it
     * exists so a tile whose fetch failed can keep the bucket it had rather than leave a hole. The
     * bucket cache cannot answer that -- a request only reaches the fetch at all because the cache
     * missed. Written and read by the layout pass alone, which is a single consumer coroutine
     * (`VectorLayer.startSymbolsProcessing`).
     */
    private var lastPublished: Map<String, SymbolBucket> = emptyMap()

    /** Stable symbol identities across tiles and zooms; see [CrossTileSymbolIndex]. */
    private val crossTileIndex = CrossTileSymbolIndex()

    /** Every symbol layer the style declares, which is what the cross-tile index is pruned against. */
    private val symbolLayerIds: Set<String> by lazy {
        configuration.style.layers.filterIsInstance<SymbolLayer>().mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Buckets whose tile has left the layout set but whose symbols may still be fading, upstream's
     * `Tile.holdingForSymbolFade`.
     *
     * [CrossTileSymbolIndex] is a faithful port and retires a tile's identities the moment its
     * bucket is absent from the list it is handed -- which is right, because upstream hands it the
     * *renderable* tile set, and a tile stays renderable while its symbols fade. This port has no
     * tile lifecycle to hook, and the list `place` is called with can be transiently short for
     * reasons that have nothing to do with what is on screen: a layout run still in flight, a tile
     * whose fetch has not landed, the one-tile ring jittering as the map pans. Every one of those
     * used to destroy that tile's identities permanently -- the cached, byte-identical
     * [SymbolBucket] coming back a frame later cannot help, because `bucketInstanceId` is non-zero
     * and there is no longer an index entry to match it against. Its labels then restarted at
     * opacity 0 while their old ids were still being faded out over them: the same label drawn
     * twice, which is what a flicker is here.
     *
     * So the *list* holds the departing bucket instead, for [SYMBOL_BUCKET_HOLD_MS], and the index
     * keeps upstream's semantics untouched. Held buckets are handed to the identity pass alone --
     * never to [PlacementOrder], because a departed tile's symbols must not compete;
     * `Placement.result` already carries them at a falling opacity.
     *
     * Keyed by the *index slot* a bucket occupies -- its layer and its canonical tile -- and not by
     * [SymbolBucket.key], which also carries the `span`. The two differ exactly where it matters: an
     * overzoomed source keeps one canonical tile as the map zooms and grows only its span, so the
     * bucket being replaced and the one replacing it land on the same slot. Held under its own key
     * the departing one would be appended after its replacement, and `addBucket` would take it as a
     * newer version of that slot -- releasing the live bucket's ids and re-indexing the dead one.
     */
    private val heldBuckets = mutableMapOf<String, HeldBucket>()

    private class HeldBucket(val bucket: SymbolBucket, var lastSeen: Long)

    /** The cross-tile index's entry key for [bucket]: one per style layer per canonical tile. */
    private fun indexSlotOf(bucket: SymbolBucket): String =
        "${bucket.layerId}/${bucket.ref.z}/${bucket.ref.x}/${bucket.ref.y}"

    /** See [heldBuckets]: [buckets] plus whatever left it less than [SYMBOL_BUCKET_HOLD_MS] ago. */
    private fun heldForSymbolFade(buckets: List<SymbolBucket>, now: Long): List<SymbolBucket> {
        val present = mutableSetOf<String>()
        for (bucket in buckets) {
            val slot = indexSlotOf(bucket)
            present += slot
            val held = heldBuckets[slot]
            if (held != null && held.bucket === bucket) held.lastSeen = now
            else heldBuckets[slot] = HeldBucket(bucket, now)
        }

        val extra = mutableListOf<SymbolBucket>()
        val entries = heldBuckets.entries.iterator()
        while (entries.hasNext()) {
            val (slot, held) = entries.next()
            if (slot in present) continue
            if (now - held.lastSeen >= SYMBOL_BUCKET_HOLD_MS) entries.remove() else extra += held.bucket
        }
        return if (extra.isEmpty()) buckets else buckets + extra
    }

    /** The previous cycle's placement, which seeds the fades and the variable-anchor choices. */
    private var previousPlacement: Placement? = null

    /**
     * The order symbols are placed in, memoized.
     *
     * It is a function of the buckets alone -- style order, `symbol-z-order` and production order
     * are all view-independent -- so it survives every viewport update, and re-sorting every symbol
     * on screen at the placement cadence would undo much of what the split bought.
     */
    private var placementOrder: PlacementOrder? = null

    /**
     * The layout pass over every visible tile: one [SymbolBucket] per style layer per canonical tile.
     *
     * Nothing here depends on where the map currently is, only on which tiles are on screen and at
     * what integer zoom, so the result is cached and reused until that changes.
     */
    internal suspend fun layoutBuckets(viewport: MVTViewport, z: Double): Result<LayoutOutcome> = withContext(
        Dispatchers.Default
    ) {
        val density = densityState.value ?: return@withContext Result.failure(LoadTileException("density is null"))

        if (viewport.tileMatrix.isEmpty()) return@withContext Result.success(LayoutOutcome.Empty)

        /* Derived from the level being laid out, never from `viewport.zoom`: the two used to be
         * able to disagree (see `VectorLayer.startSymbolsProcessing`), and a matrix of one level
         * clamped against another level's bounds resolves the wrong tiles entirely. */
        val maxTileIndex = (1 shl z.toInt()) - 1
        // Expand by 1 tile in each direction so edge symbols are collision-checked
        // against off-screen content (MapLibre-style viewport padding).
        val expandedTiles = mutableMapOf<Int, MutableSet<Int>>()

        fun expandRow(row: Int, cols: IntRange) {
            if (cols.isEmpty()) return
            val columns = expandedTiles.getOrPut(row) { mutableSetOf() }
            for (x in (cols.min() - 1).coerceAtLeast(0)..(cols.max() + 1).coerceAtMost(maxTileIndex)) {
                columns += x
            }
        }

        /* A *set* per row rather than a range, because an infinite-scroll viewport's wrap-around
         * windows are genuinely disjoint from the main one and a range would have to span the gap
         * between them -- the whole tile row, for every symbol layer in the style. */
        for (matrix in listOf(viewport.tileMatrix) + viewport.overflowTileMatrices) {
            if (matrix.isEmpty()) continue
            val rowMin = matrix.keys.min()
            val rowMax = matrix.keys.max()
            for ((row, cols) in matrix) expandRow(row, cols)
            for (adjRow in listOf(rowMin - 1, rowMax + 1)) {
                if (adjRow < 0 || adjRow > maxTileIndex) continue
                val refRow = if (adjRow < rowMin) rowMin else rowMax
                expandRow(adjRow, matrix[refRow] ?: continue)
            }
        }

        /* One bucket per *canonical* tile, as upstream. A symbol layer reading an overzoomed source
         * is laid out once over the whole ancestor rather than once per sub-square, so
         * `mergeLines`, `clipLine`, the line-anchor walk and `anchorIsTooClose` all see the geometry
         * `symbol_layout.ts` would see. Deduplicating on the ancestor also costs less than one
         * bucket per visible map tile. */
        val symbolLayers = configuration.style.layers.withIndex()
            .mapNotNull { (index, layer) -> (layer as? SymbolLayer)?.let { index to it } }
        if (symbolLayers.isEmpty()) return@withContext Result.success(LayoutOutcome.Empty)

        val refsForSource = mutableMapOf<String, MutableSet<TileRef>>()
        for ((y, columns) in expandedTiles) {
            for (x in columns) {
                val refs = resolveRefs(z = z.toInt(), x = x, y = y)
                for ((_, styleLayer) in symbolLayers) {
                    val sourceName = styleLayer.source.takeIf { !it.isNullOrBlank() } ?: continue
                    val ref = refs[sourceName] ?: continue
                    // The sub-square is what differs between the map tiles sharing one ancestor.
                    refsForSource.getOrPut(sourceName) { mutableSetOf() }
                        .add(ref.copy(subX = 0, subY = 0))
                }
            }
        }

        // Sorted so the placement pass's final tie-break (production order) is stable run to run;
        // upstream orders its tiles too (`style.ts` sorts by overscaledZ then tile id). With nothing
        // overzoomed this is exactly the old tile-outer, layer-inner order.
        val requested = symbolLayers.flatMap { (layerIndex, styleLayer) ->
            val sourceName = styleLayer.source.takeIf { !it.isNullOrBlank() }
                ?: return@flatMap emptyList()
            refsForSource[sourceName].orEmpty().map { ref ->
                BucketRequest(ref = ref, layerIndex = layerIndex, styleLayer = styleLayer, sourceName = sourceName)
            }
        }.sortedWith(compareBy({ it.ref.z }, { it.ref.y }, { it.ref.x }, { it.layerIndex }))

        val zoomLevel = z.toInt()
        val cached = mutableMapOf<String, SymbolBucket>()
        val missing = mutableListOf<BucketRequest>()
        symbolBucketCacheMutex.withLock {
            /* The live working set, not a constant. A plain LRU sized below it evicts precisely what
             * this pass is about to ask for again, so every run rebuilds most of the map's buckets
             * -- and a rebuilt bucket is one the cross-tile index has to re-derive identities for.
             * Doubled so the level being left behind during a zoom survives beside the one being
             * entered; `bucketKey` carries the integer zoom, so both are live at once. */
            symbolBucketCache.growTo(requested.size * 2)
            for (request in requested) {
                val key = bucketKey(request, zoomLevel)
                val hit = symbolBucketCache.get(key)
                if (hit != null) cached[key] = hit else missing += request
            }
        }

        /* Fetched concurrently, as the per-tile fetch used to be: the bucket loop below is
         * sequential, and awaiting one source's tile before starting the next would serialize a
         * multi-source style's requests. */
        val tiles: Map<String, Tile> = if (missing.isEmpty()) emptyMap() else supervisorScope {
            missing.map { it.sourceName to it.ref }.distinct()
                .map { (sourceName, ref) ->
                    getTileKey(sourceName, ref) to async {
                        when (sourceName) {
                            in geoJsonSourceNames -> geoJsonTile(sourceName, ref)
                            // Symbols only come from vector sources; image tiles are never fetched here.
                            in vectorSourceNames -> decodeVectorTile(sourceName, ref)
                            else -> null
                        }
                    }
                }
                .mapNotNull { (key, deferred) ->
                    val tile = runCatching { deferred.await() }.getOrNull() ?: return@mapNotNull null
                    key to tile
                }
                .toMap()
        }

        val retained = lastPublished
        var unresolved = 0
        val propCaches = mutableMapOf<String, HashMap<String, EvalFeature>>()
        for (request in missing) {
            val ref = request.ref
            val key = bucketKey(request, zoomLevel)
            val tile = tiles[getTileKey(request.sourceName, ref)]
            /* A tile that did not arrive contributes no *new* bucket -- and above all not an
             * instance-less one. A placeholder bucket is not "this tile has no symbols", it is "we do
             * not know yet", and handing one to `CrossTileSymbolIndex` installs an *empty*
             * `TileLayerIndex` at that tile's slot: it releases the ids of the bucket that was there
             * and matches nothing when the real tile lands, so every label on the tile is handed a
             * fresh identity and fades in over its own dying copy.
             *
             * What it publishes instead is [lastPublished]'s bucket for the same slot, when there is
             * one. Omitting it altogether leaves a *hole*, and a hole is a fade: every label on that
             * tile is unplaced from the next cycle on, so it fades out -- and since the layout pass
             * only re-runs when the tile set or the integer zoom changes, it stays gone until the map
             * moves and then fades back in. That is the blink. The retained bucket is the same
             * canonical tile at the same integer zoom, which is exactly the answer the fetch owed.
             *
             * A tile that *did* load and simply has no symbols for this layer still yields a real,
             * cached, instance-less bucket -- that one is an answer, and it must stay in the list. */
            if (tile == null) {
                unresolved += 1
                retained[key]?.let { cached[key] = it }
                continue
            }
            val bucket = symbolBucketBuilder.build(
                tile = tile,
                styleLayer = request.styleLayer,
                layerIndex = request.layerIndex,
                sourceName = request.sourceName,
                ref = ref,
                bucketZoom = zoomLevel,
                density = density,
                localPropCache = propCaches.getOrPut(getTileKey(request.sourceName, ref)) { HashMap() },
            )
            cached[key] = bucket
            symbolBucketCacheMutex.withLock { symbolBucketCache.put(key, bucket) }
        }

        val published = mutableMapOf<String, SymbolBucket>()
        val buckets = requested.mapNotNull { request ->
            val key = bucketKey(request, zoomLevel)
            cached[key]?.also { published[key] = it }
        }
        lastPublished = published

        Result.success(LayoutOutcome(buckets = buckets, unresolved = unresolved))
    }

    /** One style layer over one canonical tile, before its bucket exists. */
    private class BucketRequest(
        val ref: TileRef,
        val layerIndex: Int,
        val styleLayer: SymbolLayer,
        val sourceName: String,
    )

    /** A bucket is identified by the tile *fetched*, the style layer, and the zoom it was built at. */
    private fun bucketKey(request: BucketRequest, zoomLevel: Int): String =
        "${getRenderKey(request.sourceName, request.ref)}-L${request.layerIndex}-z$zoomLevel"

    /**
     * The placement pass, scheduled the way upstream schedules it
     * (`maplibre-gl-js/src/style/style.ts`'s `_updatePlacement`).
     *
     * The important half is the *refusal*. A placement is not recomputed whenever the view moves:
     * upstream starts a new one only once the last is no longer [Placement.stillRecent] -- at most
     * once per fade duration -- and until then the committed decisions are held while the draw pass
     * keeps re-projecting and re-scaling them. Recomputing per viewport update, which this port did,
     * makes every near-threshold label re-decide sixty times a second, and that is what a pinch
     * looked like: shimmering labels.
     *
     * Returns how long to wait before reconsidering, or 0 when nothing is pending. Upstream re-enters
     * `_updatePlacement` on every render frame so a deferred placement is picked up as soon as the
     * window lapses; this port is driven by viewport events, so the caller retries instead.
     */
    internal suspend fun place(
        buckets: List<SymbolBucket>,
        viewportInfo: ViewportInfo,
        now: Long,
    ): PlacementOutcome = withContext(Dispatchers.Default) {
        /* Identities first, as upstream does before the scheduling decision: fading and the
         * variable-anchor memory are both keyed on them, a bucket that has not changed keeps the ids
         * it already has, and whether any changed is half of `placementInputsChanged`. */
        var symbolBucketsChanged = false
        val density = densityState.value?.density ?: 1f
        for ((layerId, layerBuckets) in heldForSymbolFade(buckets, now).groupBy { it.layerId }) {
            if (crossTileIndex.addLayer(layerId, layerBuckets, density)) symbolBucketsChanged = true
        }
        /* Pruned against the *style*, which is what upstream's `pruneUnusedLayers` is for -- a layer
         * the style no longer has. Pruning against this cycle's buckets instead meant one call whose
         * list happened to be short, or empty, wiped every identity on the map. */
        crossTileIndex.pruneUnusedLayers(symbolLayerIds)

        val previous = previousPlacement

        /* Upstream's `placementInputsChanged`. Its own test is
         * `!mat4.exactEquals(lastPlacement.transform.modelViewProjectionMatrix, transform.…)` --
         * "did the view move at all" -- which here is the viewport the last placement was taken
         * against. */
        val inputsChanged = symbolBucketsChanged || previous == null || previous.viewport != viewportInfo

        /* Called exactly once per cycle, as upstream calls it once inside `placementSettled`: it
         * carries `zoomAtLastRecencyCheck` forward. */
        val settled = previous == null || !previous.stillRecent(now, viewportInfo.fractionalZoom)

        if (previous == null || (settled && (inputsChanged || previous.isStale))) {
            val order = placementOrder?.takeIf { it.matches(buckets, viewportInfo) }
                ?: PlacementOrder(buckets, viewportInfo).also { placementOrder = it }

            val placement = Placement(
                viewportInfo = viewportInfo,
                zoom = viewportInfo.fractionalZoom,
                collisionDetectionEnabled = configuration.collisionDetectionEnabled,
            )
            placement.placeBuckets(order, previous)
            placement.commit(previous, now)
            previousPlacement = placement
            PlacementOutcome(result = placement.result(previous), retryInMs = 0L)
        } else {
            /* Upstream's "mark it stale to ensure that we request another render frame"; if nothing
             * changed it stays clean and the map can go idle. */
            if (inputsChanged) previous.setStale()
            PlacementOutcome(
                result = null,
                retryInMs = if (previous.isStale) previous.recencyRemainingMs(now).coerceAtLeast(1L) else 0L,
            )
        }
    }
}

/**
 * The **floor** on how many laid-out symbol buckets to keep; the real size is the viewport's.
 *
 * A bucket is one style layer over one canonical tile, and the layout pass covers the visible tile
 * matrix plus a one-tile ring for *every* symbol layer in the style, so the live count is
 * `(rows + 2) * (cols + 2) * symbolLayers` -- and real styles have a lot of those layers: 28 in
 * `test_style_bright.json`, 33 in `test_style_street_v2.json`. A phone showing a 3x4 matrix is
 * already `5 * 6 * 28 = 840`, and two adjacent integer zooms are live at once during a pinch because
 * `bucketKey` carries the zoom, so no constant can be right for every viewport. This one was sized
 * from the nine tiles of a *unit-test* viewport, which put it an order of magnitude under a phone's
 * working set: the cache then evicted precisely what the next run asked for, the layout pass rebuilt
 * most of the map every time it ran, and each rebuilt bucket forced the cross-tile index to
 * re-derive its identities -- the thing the layout/placement split exists to stop.
 *
 * [ovh.plrapps.mapcompose.vector.utils.LruCache.growTo] raises it to the live set on every layout
 * pass; this is only what an empty or tiny viewport falls back to.
 */
private const val SYMBOL_BUCKET_CACHE_SIZE = 512

/**
 * How long a bucket that has left the layout set keeps its cross-tile identities.
 *
 * Two intervals have to fit inside it. A placement runs at most once per fade duration
 * ([Placement.stillRecent]), so a bucket has to survive a whole cycle of absence before its
 * disappearance can be called real rather than a list that was transiently short. And once it is
 * real, the symbols on it are still fading out for a fade duration more -- upstream's
 * `Tile.holdingForSymbolFade`. Hence the sum.
 */
private val SYMBOL_BUCKET_HOLD_MS = 2 * SYMBOL_FADE_DURATION_MS

/**
 * How many built `Path`s and rasterized labels to keep.
 *
 * Shared between the tile painters' geometry and `TextLabelBuilder`'s label bitmaps, and sized for
 * the same nine-tiles-by-N-layers reason as [SYMBOL_BUCKET_CACHE_SIZE]: a dense tile carries
 * hundreds of distinct labels, and at 200 entries a label was re-shaped and re-rasterized nearly
 * every time its bucket was rebuilt.
 */
private const val PATH_CACHE_SIZE = 1024

/**
 * What one call to [VectorRasterizer.layoutBuckets] produced.
 *
 * [unresolved] counts the requests whose tile did not arrive. Such a request still contributes the
 * bucket the previous pass published for it, so nothing on screen loses its labels, but the pass as a
 * whole is *incomplete*: the layout only re-runs when the tile set or the integer zoom changes, so
 * without a retry a tile that failed once would keep the bucket it had -- or have none at all, the
 * first time round -- until the map moved. `VectorLayer` retries while this is non-zero.
 */
internal class LayoutOutcome(
    val buckets: List<SymbolBucket>,
    val unresolved: Int,
) {
    val isComplete: Boolean get() = unresolved == 0

    companion object {
        val Empty = LayoutOutcome(emptyList(), unresolved = 0)
    }
}

/**
 * What one call to [VectorRasterizer.place] decided.
 *
 * [result] is null when the cycle was deferred -- the previous placement is still recent -- and
 * [retryInMs] then says when to ask again, which is 0 when nothing is pending at all.
 */
internal class PlacementOutcome(
    val result: ovh.plrapps.mapcompose.vector.symbol.PlacementResult?,
    val retryInMs: Long,
)

/**
 * Provides information about the current state of the viewport in a MapLibre-like map renderer.
 *
 * @property matrix The current tile transformation matrix, describing how map tiles are projected and positioned in the viewport.
 * @property overflowMatrices The wrap-around tile windows an infinite-scroll viewport also shows.
 *   Held apart from [matrix] rather than merged into it: a [TileMatrix] is one contiguous column
 *   range per row, and merging a window at the far edge of the world with one at the near edge
 *   claims every tile between them.
 * @property size The pixel size (width and height) of the viewport.
 * @property angleRad The rotation angle of the viewport, in radians.
 * @property pitch The pitch (tilt) of the viewport, in degrees (0 = looking straight down).
 * @property zoom The integer tile level currently displayed, which is the zoom symbol layout is
 *   evaluated at and the zoom a [ovh.plrapps.mapcompose.vector.symbol.SymbolBucket] is built for.
 * @property fractionalZoom The continuous map zoom, which is what the placement pass evaluates
 *   `text-size` and `icon-size` at. Layout bakes a symbol at its bucket's zoom; this is what says
 *   how much smaller than that it should be drawn right now.
 */
data class ViewportInfo(
    val matrix: TileMatrix,
    val size: IntSize,
    val angleRad: AngleRad,
    val pitch: Float,
    val zoom: Int,
    val fractionalZoom: Double = zoom.toDouble(),

    // Snapshot values
    val centroidX: Double,
    val centroidY: Double,
    val scale: Double,
    val fullWidth: Int,
    val fullHeight: Int,
    val infiniteScrollX: Boolean = false,
    val visiblePhases: IntRange = 0..0,
    val overflowMatrices: List<TileMatrix> = emptyList(),
)

class LoadTileException(msg: String) : Exception(msg)