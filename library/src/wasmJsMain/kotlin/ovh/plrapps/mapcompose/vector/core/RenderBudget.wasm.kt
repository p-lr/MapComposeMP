package ovh.plrapps.mapcompose.vector.core

internal actual fun defaultRenderBudgetBytes(): Long = 64 * MB

internal actual fun defaultCacheBudgetBytes(): Long = 64 * MB

/** One thread: rasterizing two tiles "at once" only interleaves them and doubles what is held. */
internal actual fun defaultMaxConcurrentRenders(): Int = 1
