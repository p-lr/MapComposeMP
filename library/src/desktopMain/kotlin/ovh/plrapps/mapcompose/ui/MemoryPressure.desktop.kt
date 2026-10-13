package ovh.plrapps.mapcompose.ui

import androidx.compose.runtime.Composable

/** There is no memory-pressure signal on this platform. */
@Composable
internal actual fun MemoryPressureEffect(onTrim: (critical: Boolean) -> Unit) = Unit
