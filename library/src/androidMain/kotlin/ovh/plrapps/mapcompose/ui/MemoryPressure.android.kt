package ovh.plrapps.mapcompose.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun MemoryPressureEffect(onTrim: (critical: Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val callback by rememberUpdatedState(onTrim)
    DisposableEffect(context) {
        val callbacks = object : ComponentCallbacks2 {
            @Suppress("DEPRECATION") // The running levels are deprecated from API 34, but still sent below it.
            override fun onTrimMemory(level: Int) {
                when {
                    // Includes TRIM_MEMORY_UI_HIDDEN: nothing of the map is on screen any more.
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> callback(true)
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> callback(false)
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = callback(true)
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }
}
