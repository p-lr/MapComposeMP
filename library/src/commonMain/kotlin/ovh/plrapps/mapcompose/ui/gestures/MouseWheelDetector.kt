package ovh.plrapps.mapcompose.ui.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.pow
import kotlin.math.sign

internal suspend fun PointerInputScope.detectMouseWheelGesture(
    mouseWheelZoomFactor: Double,
    onZoom: (Double, Offset) -> Unit
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                val change = event.changes.firstOrNull() ?: continue
                val dy = change.scrollDelta.y
                if (dy != 0f && mouseWheelZoomFactor > 1.0) {
                    val scaleRatio = mouseWheelZoomFactor.pow(-dy.sign.toDouble())
                    onZoom(scaleRatio, change.position)
                    /* Event not consumed to allow for panning and zooming at the same time. */
                }
            }
        }
    }
}
