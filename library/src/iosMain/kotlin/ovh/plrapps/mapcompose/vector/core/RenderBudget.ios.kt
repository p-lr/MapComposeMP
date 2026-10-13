package ovh.plrapps.mapcompose.vector.core

internal actual fun defaultRenderBudgetBytes(): Long = 96 * MB

internal actual fun defaultCacheBudgetBytes(): Long = 96 * MB

internal actual fun defaultMaxConcurrentRenders(): Int =
    (ovh.plrapps.mapcompose.ui.state.getProcessorCount() - 1).coerceAtLeast(1)
