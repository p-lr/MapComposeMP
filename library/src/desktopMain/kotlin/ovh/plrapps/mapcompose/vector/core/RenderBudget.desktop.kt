package ovh.plrapps.mapcompose.vector.core

internal actual fun defaultRenderBudgetBytes(): Long = 256 * MB

internal actual fun defaultCacheBudgetBytes(): Long = 256 * MB

internal actual fun defaultMaxConcurrentRenders(): Int =
    (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
