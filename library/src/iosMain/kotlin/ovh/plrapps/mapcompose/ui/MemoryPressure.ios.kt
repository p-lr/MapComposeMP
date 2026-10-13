package ovh.plrapps.mapcompose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

@Composable
internal actual fun MemoryPressureEffect(onTrim: (critical: Boolean) -> Unit) {
    val callback by rememberUpdatedState(onTrim)
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = UIApplicationDidReceiveMemoryWarningNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> callback(true) }
        onDispose { center.removeObserver(observer) }
    }
}
