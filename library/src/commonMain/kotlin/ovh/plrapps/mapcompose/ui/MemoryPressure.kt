package ovh.plrapps.mapcompose.ui

import androidx.compose.runtime.Composable

/**
 * Calls [onTrim] when the platform says memory is short, for as long as it is in the composition.
 *
 * [onTrim]'s argument is `true` when the pressure is critical -- the app is about to be killed, or
 * has left the screen -- and `false` when it is only moderate. Android reports both through
 * `ComponentCallbacks2`; iOS only ever sends a memory warning, which counts as critical. Desktop and
 * the browser have no such signal.
 */
@Composable
internal expect fun MemoryPressureEffect(onTrim: (critical: Boolean) -> Unit)
