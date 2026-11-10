@file:OptIn(ExperimentalAtomicApi::class)

package ovh.plrapps.mapcompose.vector.core

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Where the vector layer spends its time and its memory, for diagnosing a slow or memory-hungry map.
 *
 * Off by default, and then every [measure] is a single branch. Turn it on from an app or a demo,
 * pan and zoom for a while, and read [report]:
 *
 * ```
 * VectorStats.enabled = true
 * ...
 * println(VectorStats.report())
 * ```
 *
 * Times are wall-clock per call, summed across every thread that rasterizes, so a phase's total can
 * exceed the time that elapsed. Not a stable API: the phases follow the implementation.
 */
object VectorStats {
    @Volatile
    var enabled: Boolean = false

    enum class Phase {
        /** A tile's bytes, network or byte cache. */
        FETCH,

        /** Protobuf bytes into a `Tile`. */
        DECODE,

        /** One whole tile rasterization, every style layer included. */
        RENDER,

        /** The super-sampled bitmap filtered down to the requested size. */
        DOWNSAMPLE,

        /** Getting the rasterized tile into core's hands. */
        HANDOVER,

        LAYER_BACKGROUND,
        LAYER_FILL,
        LAYER_LINE,
        LAYER_CIRCLE,
        LAYER_RASTER,
        LAYER_HILLSHADE,
        LAYER_COLOR_RELIEF,
        LAYER_HEATMAP,

        /** One symbol layout pass over the viewport. */
        LAYOUT,

        /** One placement pass that actually ran. */
        PLACEMENT,
    }

    private val nanos = Array(Phase.entries.size) { AtomicLong(0L) }
    private val counts = Array(Phase.entries.size) { AtomicLong(0L) }

    private val bitmapBytesInFlight = AtomicLong(0L)
    private val peakBitmapBytesInFlight = AtomicLong(0L)
    private val tilesSkipped = AtomicLong(0L)

    inline fun <T> measure(phase: Phase, block: () -> T): T {
        if (!enabled) return block()
        val mark = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            record(phase, mark.elapsedNow())
        }
    }

    fun record(phase: Phase, elapsed: Duration) {
        nanos[phase.ordinal].addAndFetch(elapsed.inWholeNanoseconds)
        counts[phase.ordinal].addAndFetch(1L)
    }

    /** A tile bitmap of [bytes] was allocated by the rasterizer and is not yet handed over. */
    fun bitmapAllocated(bytes: Long) {
        if (!enabled) return
        val now = bitmapBytesInFlight.addAndFetch(bytes)
        while (true) {
            val peak = peakBitmapBytesInFlight.load()
            if (now <= peak || peakBitmapBytesInFlight.compareAndSet(peak, now)) break
        }
    }

    /** The counterpart of [bitmapAllocated]. */
    fun bitmapReleased(bytes: Long) {
        if (!enabled) return
        bitmapBytesInFlight.addAndFetch(-bytes)
    }

    /** A tile request that was dropped because it was no longer visible. */
    fun tileSkipped() {
        if (!enabled) return
        tilesSkipped.addAndFetch(1L)
    }

    fun reset() {
        for (i in nanos.indices) {
            nanos[i].store(0L)
            counts[i].store(0L)
        }
        peakBitmapBytesInFlight.store(bitmapBytesInFlight.load())
        tilesSkipped.store(0L)
    }

    /** Total milliseconds spent in [phase] since the last [reset]. */
    fun totalMillis(phase: Phase): Double = nanos[phase.ordinal].load() / 1_000_000.0

    /** How many times [phase] ran since the last [reset]. */
    fun count(phase: Phase): Long = counts[phase.ordinal].load()

    val peakBitmapBytes: Long get() = peakBitmapBytesInFlight.load()

    fun report(): String = buildString {
        appendLine("VectorStats (ms total / calls / ms per call)")
        for (phase in Phase.entries) {
            val count = count(phase)
            if (count == 0L) continue
            val total = totalMillis(phase)
            appendLine(
                "  ${phase.name.padEnd(20)} ${format(total).padStart(10)} ${count.toString().padStart(7)} " +
                    format(total / count).padStart(9)
            )
        }
        appendLine("  peak tile bitmap bytes in flight: ${peakBitmapBytes / (1024 * 1024)} MB")
        val skipped = tilesSkipped.load()
        if (skipped > 0) appendLine("  stale tiles skipped: $skipped")
    }

    private fun format(value: Double): String {
        val rounded = kotlin.math.round(value * 10) / 10
        return rounded.toString()
    }
}
