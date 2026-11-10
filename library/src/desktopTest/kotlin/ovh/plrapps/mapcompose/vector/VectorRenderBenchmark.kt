package ovh.plrapps.mapcompose.vector

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.core.adoptRenderedBitmap
import ovh.plrapps.mapcompose.vector.core.VectorRasterizer
import ovh.plrapps.mapcompose.vector.core.VectorStats
import ovh.plrapps.mapcompose.vector.core.ViewportInfo
import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.vector.core.vectorTileRaster
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Measures the vector tile pipeline end to end on real tiles: fetch, decode, rasterize, hand over.
 *
 * **Skipped unless `MAPCOMPOSE_BENCH` is set**, so the test gate never pays for it or for the
 * network. Run it with
 *
 * ```
 * MAPCOMPOSE_BENCH=1 ./gradlew :library:desktopTest --tests "*VectorRenderBenchmark*" --rerun
 * ```
 *
 * and read `library/build/bench-report.txt`. `MAPCOMPOSE_BENCH_ONLY=desktop-2x/z14` narrows the run. Every network resource (style, TileJSON, sprites,
 * glyph ranges, tiles) is cached under `library/build/bench-cache/` on the first run, so later runs
 * are offline and comparable; nothing binary is committed.
 *
 * The scenarios are the swisstopo basemap the demo shows, around Zurich: at the sources' own
 * `maxzoom` (span 1) and overzoomed (span 4 and 8), each at the bitmap size the demo asks for on a
 * 2x desktop and on a 3x phone.
 */
class VectorRenderBenchmark {

    @Test
    fun benchmark() {
        if (System.getenv("MAPCOMPOSE_BENCH").isNullOrEmpty()) return
        runBlocking { run() }
    }

    private class Profile(val name: String, val density: Float, val magnifyingFactor: Int, val superSampling: Int)

    private class Scenario(val name: String, val z: Int)

    private val profiles = listOf(
        Profile("desktop-2x", density = 2f, magnifyingFactor = 1, superSampling = 2),
        Profile("phone-3x", density = 3f, magnifyingFactor = 2, superSampling = 2),
    )

    private val scenarios = listOf(
        Scenario("z10 span1", 10),
        Scenario("z11 span1", 11),
        Scenario("z12 span1", 12),
        Scenario("z13 span1", 13),
        Scenario("z14 span1", 14),
        Scenario("z16 span4", 16),
        Scenario("z17 span8", 17),
        Scenario("z18 span16", 18),
        Scenario("z19 span32", 19),
        Scenario("z20 span64", 20),
    )

    /** `MAPCOMPOSE_BENCH_ONLY=desktop-2x/z14` runs only the scenarios whose name contains it. */
    private val only: String? = System.getenv("MAPCOMPOSE_BENCH_ONLY")?.takeIf { it.isNotEmpty() }

    private val report = StringBuilder()

    private fun log(line: String = "") {
        println(line)
        report.appendLine(line)
    }

    private suspend fun run() {
        val style = String(fetchCached(STYLE_URL) ?: error("style unavailable"))
        val configuration = getMapLibreConfiguration(
            style = style,
            loadResource = { url -> fetchCached(url)?.let { Buffer().apply { write(it) } } },
        ).getOrThrow()

        log("Vector render benchmark -- ${STYLE_URL.substringAfterLast("/styles/")}")
        log("style layers: ${configuration.style.layers.size}")
        log()

        measureDecodedTiles(configuration)

        for (profile in profiles) {
            val raster = vectorTileRaster(
                tileSize = TILE_SIZE,
                density = profile.density,
                superSampling = profile.superSampling,
                magnifyingFactor = profile.magnifyingFactor,
            )
            for (scenario in scenarios) {
                if (only != null && only !in "${profile.name}/${scenario.name}") continue
                runScenario(configuration, profile, scenario, raster.renderPx, raster.superSampling)
            }
        }

        File(System.getProperty("user.dir"), "build/bench-report.txt").apply {
            parentFile.mkdirs()
            writeText(report.toString())
        }
    }

    private suspend fun runScenario(
        configuration: ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration,
        profile: Profile,
        scenario: Scenario,
        bitmapPx: Int,
        superSampling: Int,
    ) {
        fun newRasterizer() = VectorRasterizer(
            configuration = configuration,
            densityState = MutableStateFlow(Density(profile.density)),
            fontFamilyResolverState = MutableStateFlow<FontFamily.Resolver?>(null),
            textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
            getTileStream = { url, _, _, _ -> fetchCached(url)?.let { Buffer().apply { write(it) } as RawSource } },
        )
        val rasterizer = newRasterizer()
        val (cx, cy) = tileOf(ZURICH_LON, ZURICH_LAT, scenario.z)
        val tiles = (-1..1).flatMap { dy -> (-1..1).map { dx -> (cx + dx) to (cy + dy) } }

        // Warm-up: fills the caches the app would have filled too, and lets the JIT settle.
        for ((x, y) in tiles) render(rasterizer, x, y, scenario.z, bitmapPx, superSampling)

        VectorStats.reset()
        VectorStats.enabled = true
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val allocatedBefore = threads.currentThreadAllocatedBytes
        val start = TimeSource.Monotonic.markNow()
        for (pass in 0 until PASSES) {
            for ((x, y) in tiles) {
                render(rasterizer, x, y, scenario.z, bitmapPx, superSampling)
            }
        }
        val elapsedMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        val allocated = threads.currentThreadAllocatedBytes - allocatedBefore
        VectorStats.enabled = false
        val count = tiles.size * PASSES

        /* A first visit: everything but the encoded bytes is rebuilt -- the decode, the geometry, the
         * evaluated features -- which is what panning onto new ground costs. */
        val coldStart = TimeSource.Monotonic.markNow()
        for ((x, y) in tiles) {
            rasterizer.trimMemory(critical = false)
            render(rasterizer, x, y, scenario.z, bitmapPx, superSampling)
        }
        val coldMs = coldStart.elapsedNow().inWholeMicroseconds / 1000.0 / tiles.size

        /* The layout pass on a rasterizer of its own, so its label and bucket caches are cold, as on
         * a first visit -- after one pass on another, so the JIT and the glyph ranges are not. */
        measureLayout(newRasterizer(), scenario.z, cx, cy, profile)
        val symbols = measureLayout(newRasterizer(), scenario.z, cx, cy, profile)

        log("== ${profile.name} / ${scenario.name}: render ${bitmapPx}px, ss $superSampling")
        log("  per tile: ${fmt(elapsedMs / count)} ms total; " +
            "allocated ${fmt(allocated / count / 1e6)} MB (JVM heap, this thread)")
        log("  cold per tile (caches trimmed, bytes kept): ${fmt(coldMs)} ms")
        log("  layout pass, cold caches (3x3 + ring): ${fmt(symbols.layoutMs)} ms, ${symbols.buckets} buckets, " +
            "${symbols.instances} instances, ~${fmt(symbols.retainedBytes / 1e6)} MB retained")
        log("  first placement, rasterizing what it places: ${fmt(symbols.placementMs)} ms, ${symbols.placed} symbols")
        VectorStats.report().lines().forEach { if (it.isNotBlank()) log("  $it") }
        log()
    }

    /** One tile through the whole pipeline, up to core owning the bitmap. */
    private suspend fun render(
        rasterizer: VectorRasterizer,
        x: Int,
        y: Int,
        z: Int,
        bitmapPx: Int,
        superSampling: Int,
    ) {
        val bitmap = checkNotNull(
            rasterizer.getTile(x = x, y = y, zoom = z.toDouble(), tileSize = bitmapPx, superSampling = superSampling)
        )
        VectorStats.measure(VectorStats.Phase.HANDOVER) {
            VectorStats.bitmapReleased(bitmap.width.toLong() * bitmap.height * 4)
            adoptRenderedBitmap(bitmap, hasLayers = false)
        }
    }

    /** One cold layout pass and the first placement over it: (layout ms, placement ms, symbols placed). */
    private suspend fun measureLayout(
        rasterizer: VectorRasterizer,
        z: Int,
        cx: Int,
        cy: Int,
        profile: Profile,
    ): SymbolMeasure {
        val matrix = (cy - 1..cy + 1).associateWith { cx - 1..cx + 1 }
        val viewport = MVTViewport(
            width = 1080f,
            height = 1920f,
            bearing = 0f,
            pitch = 0f,
            zoom = z.toFloat(),
            tileMatrix = matrix,
        )
        val heapBefore = usedHeap()
        val start = TimeSource.Monotonic.markNow()
        val buckets = rasterizer.layoutBuckets(viewport, z.toDouble()).getOrThrow().buckets
        val layoutMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        // Everything the pass leaves alive -- buckets, label cache, and the tile caches, which dominate.
        val retained = usedHeap() - heapBefore

        // The map at integer zoom [z], centred on the middle tile, one tile pixel per screen pixel.
        val worldPx = (TILE_SIZE * profile.density).toInt() * (1 shl z)
        val info = ViewportInfo(
            matrix = matrix,
            size = IntSize(1080, 1920),
            angleRad = 0f,
            pitch = 0f,
            zoom = z,
            fractionalZoom = z.toDouble(),
            centroidX = (cx + 0.5) / (1 shl z),
            centroidY = (cy + 0.5) / (1 shl z),
            scale = 1.0,
            fullWidth = worldPx,
            fullHeight = worldPx,
        )
        val placementStart = TimeSource.Monotonic.markNow()
        val result = rasterizer.place(buckets, info, now = 0L).result
        val placementMs = placementStart.elapsedNow().inWholeMicroseconds / 1000.0
        return SymbolMeasure(
            layoutMs = layoutMs,
            placementMs = placementMs,
            placed = result?.symbols?.size ?: 0,
            buckets = buckets.size,
            instances = buckets.sumOf { it.instances.size },
            retainedBytes = retained,
        )
    }

    private class SymbolMeasure(
        val layoutMs: Double,
        val placementMs: Double,
        val placed: Int,
        val buckets: Int,
        val instances: Int,
        val retainedBytes: Long,
    )

    /** How big a decoded tile is next to its bytes. */
    private suspend fun measureDecodedTiles(configuration: ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration) {
        val (cx, cy) = tileOf(ZURICH_LON, ZURICH_LAT, 14)
        val payloads = configuration.tileSources.values.flatMap { source ->
            (-1..1).flatMap { dy ->
                (-1..1).mapNotNull { dx ->
                    fetchCached(source.getTileUrl(TileRef.whole(z = 14, x = cx + dx, y = cy + dy)))
                }
            }
        }
        val rawBytes = payloads.sumOf { it.size.toLong() }

        val decodeStart = TimeSource.Monotonic.markNow()
        val before = usedHeap()
        val decoded = payloads.map { Tile.decodeFromByteArray(it) }
        val decodeMs = decodeStart.elapsedNow().inWholeMicroseconds / 1000.0
        val after = usedHeap()
        val ints = decoded.sumOf { tile ->
            tile.layers.sumOf { layer -> layer.features.sumOf { (it.geometry.size + it.tags.size).toLong() } }
        }
        val features = decoded.sumOf { tile -> tile.layers.sumOf { it.features.size.toLong() } }

        log("Decoded tiles (z14, 3x3, every source): ${payloads.size} tiles, ${fmt(rawBytes / 1e6)} MB of bytes")
        log("  decode: ${fmt(decodeMs / payloads.size)} ms per tile")
        log("  retained after decode: ~${fmt((after - before) / 1e6)} MB " +
            "(${fmt((after - before).toDouble() / rawBytes)}x the bytes); $features features, $ints geometry+tag ints")
        log()
        check(decoded.isNotEmpty())
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun fmt(value: Double): String = "%.1f".format(value)

    private fun tileOf(lon: Double, lat: Double, z: Int): Pair<Int, Int> {
        val n = (1 shl z).toDouble()
        val latRad = lat * PI / 180
        val x = floor((lon + 180) / 360 * n).toInt()
        val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n).toInt()
        return x to y
    }

    private fun fetchCached(url: String): ByteArray? {
        if (!url.startsWith("http")) return null
        val dir = File(System.getProperty("user.dir"), "build/bench-cache").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, key)
        if (file.exists()) return file.readBytes().takeIf { it.isNotEmpty() }?.let(::gunzipIfNeeded)
        val bytes = runCatching {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MapComposeMP-benchmark")
            }
            connection.getInputStream().use { it.readBytes() }
        }.getOrNull()
        // A missing resource is cached as empty so the next run does not retry it over the network.
        file.writeBytes(bytes ?: ByteArray(0))
        return bytes?.let(::gunzipIfNeeded)
    }

    /** swisstopo serves TileJSON and tiles gzip-encoded whatever the request says. */
    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else {
            bytes
        }

    private companion object {
        const val STYLE_URL = "https://vectortiles.geo.admin.ch/styles/ch.swisstopo.basemap.vt/style.json"
        const val TILE_SIZE = 512
        const val PASSES = 2
        const val ZURICH_LON = 8.5417
        const val ZURICH_LAT = 47.3769
    }
}
