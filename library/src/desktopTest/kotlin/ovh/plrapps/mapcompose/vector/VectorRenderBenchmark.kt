package ovh.plrapps.mapcompose.vector

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.core.decodeFirstLayer
import ovh.plrapps.mapcompose.core.makeWorkerData
import ovh.plrapps.mapcompose.vector.core.VectorRasterizer
import ovh.plrapps.mapcompose.vector.core.VectorStats
import ovh.plrapps.mapcompose.vector.core.vectorTileRaster
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.extension.toBytes
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport
import ovh.plrapps.mapcompose.vector.spec.Tile
import pbandk.decodeFromByteArray
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
        Scenario("z14 span1", 14),
        Scenario("z16 span4", 16),
        Scenario("z17 span8", 17),
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
        val rasterizer = VectorRasterizer(
            configuration = configuration,
            densityState = MutableStateFlow(Density(profile.density)),
            fontFamilyResolverState = MutableStateFlow<FontFamily.Resolver?>(null),
            textMeasurerState = MutableStateFlow<TextMeasurer?>(null),
            getTileStream = { url, _, _, _ -> fetchCached(url)?.let { Buffer().apply { write(it) } as RawSource } },
        )
        val (cx, cy) = tileOf(ZURICH_LON, ZURICH_LAT, scenario.z)
        val tiles = (-1..1).flatMap { dy -> (-1..1).map { dx -> (cx + dx) to (cy + dy) } }

        // Warm-up: fills the caches the app would have filled too, and lets the JIT settle.
        for ((x, y) in tiles) render(rasterizer, x, y, scenario.z, bitmapPx, superSampling)

        VectorStats.reset()
        VectorStats.enabled = true
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val allocatedBefore = threads.currentThreadAllocatedBytes
        val start = TimeSource.Monotonic.markNow()
        var handoverMs = 0.0
        var decodeMs = 0.0
        for (pass in 0 until PASSES) {
            for ((x, y) in tiles) {
                val (h, d) = render(rasterizer, x, y, scenario.z, bitmapPx, superSampling)
                handoverMs += h
                decodeMs += d
            }
        }
        val elapsedMs = start.elapsedNow().inWholeMicroseconds / 1000.0
        val allocated = threads.currentThreadAllocatedBytes - allocatedBefore
        VectorStats.enabled = false
        val count = tiles.size * PASSES

        val layoutMs = measureLayout(rasterizer, scenario.z, cx, cy)

        log("== ${profile.name} / ${scenario.name}: render ${bitmapPx}px, ss $superSampling")
        log("  per tile: ${fmt(elapsedMs / count)} ms total, of which core decode ${fmt(decodeMs / count)} ms; " +
            "allocated ${fmt(allocated / count / 1e6)} MB (JVM heap, this thread)")
        log("  first layout pass (3x3 + ring): ${fmt(layoutMs)} ms")
        VectorStats.report().lines().forEach { if (it.isNotBlank()) log("  $it") }
        log("  (handover = toBytes + Buffer; core decode = decodeFirstLayer, ${fmt(handoverMs / count)} / ${fmt(decodeMs / count)} ms per tile)")
        log()
    }

    /** One tile through the whole pipeline. Returns (handover ms, core decode ms). */
    private suspend fun render(
        rasterizer: VectorRasterizer,
        x: Int,
        y: Int,
        z: Int,
        bitmapPx: Int,
        superSampling: Int,
    ): Pair<Double, Double> {
        val bitmap = rasterizer.getTile(x = x, y = y, zoom = z.toDouble(), tileSize = bitmapPx, superSampling = superSampling)
        val handoverStart = TimeSource.Monotonic.markNow()
        val bytes = VectorStats.measure(VectorStats.Phase.HANDOVER) {
            val bytes = bitmap.toBytes() ?: return 0.0 to 0.0
            VectorStats.bitmapReleased(bitmap.width.toLong() * bitmap.height * 4)
            bytes
        }
        val buffer = Buffer().apply { write(bytes) }
        val handoverMs = handoverStart.elapsedNow().inWholeMicroseconds / 1000.0
        val decodeStart = TimeSource.Monotonic.markNow()
        checkNotNull(
            buffer.decodeFirstLayer(
                hasLayers = false,
                optimizeForLowEndDevices = false,
                subSamplingRatio = 1,
                workerData = makeWorkerData(),
            )
        )
        return handoverMs to decodeStart.elapsedNow().inWholeMicroseconds / 1000.0
    }

    private suspend fun measureLayout(rasterizer: VectorRasterizer, z: Int, cx: Int, cy: Int): Double {
        val viewport = MVTViewport(
            width = 1080f,
            height = 1920f,
            bearing = 0f,
            pitch = 0f,
            zoom = z.toFloat(),
            tileMatrix = (cy - 1..cy + 1).associateWith { cx - 1..cx + 1 },
        )
        val start = TimeSource.Monotonic.markNow()
        rasterizer.layoutBuckets(viewport, z.toDouble()).getOrThrow()
        return start.elapsedNow().inWholeMicroseconds / 1000.0
    }

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
