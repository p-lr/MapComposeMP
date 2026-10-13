package ovh.plrapps.mapcompose.vector.core

/** A quarter of the heap limit, which is what the device class says the app may use. */
internal actual fun defaultRenderBudgetBytes(): Long =
    (Runtime.getRuntime().maxMemory() / 4).coerceIn(32 * MB, 128 * MB)

/** Decoded tiles live on the JVM heap here, so this too is a quarter of the heap limit. */
internal actual fun defaultCacheBudgetBytes(): Long =
    (Runtime.getRuntime().maxMemory() / 4).coerceIn(32 * MB, 128 * MB)

/** One render per 64 MB of heap limit: four on a common 256 MB device, never fewer than two. */
internal actual fun defaultMaxConcurrentRenders(): Int =
    (Runtime.getRuntime().maxMemory() / (64 * MB)).toInt()
        .coerceIn(2, (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(2))
