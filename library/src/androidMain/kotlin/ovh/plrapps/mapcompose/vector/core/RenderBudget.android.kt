package ovh.plrapps.mapcompose.vector.core

/** A quarter of the heap limit, which is what the device class says the app may use. */
internal actual fun defaultRenderBudgetBytes(): Long =
    (Runtime.getRuntime().maxMemory() / 4).coerceIn(32 * MB, 128 * MB)
