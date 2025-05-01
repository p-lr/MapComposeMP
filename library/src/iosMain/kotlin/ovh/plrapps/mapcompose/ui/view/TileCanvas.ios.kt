package ovh.plrapps.mapcompose.ui.view

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposePaint
import androidx.compose.ui.graphics.asSkiaColorFilter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Paint

actual class PaintPlatform(val paint: Paint)

actual fun makePaintPlatform(): PaintPlatform {
    return PaintPlatform(
        Paint().apply { isAntiAlias = false }
    )
}

actual fun updateFilterBitmap(paintPlatform: PaintPlatform, isFilteringBitmap: () -> Boolean) {
    // No-op : this feature isn't implemented by skiko
}

actual fun setTilePaintProperties(
    paintPlatform: PaintPlatform,
    alpha: Int,
    colorFilter: ColorFilter?
) {
    paintPlatform.paint.apply {
        this.alpha = alpha
        this.colorFilter = colorFilter?.asSkiaColorFilter()
    }
}

actual fun drawTileIntoCanvas(
    canvas: Canvas,
    bitmap: ImageBitmap,
    paintPlatform: PaintPlatform,
    l: Int,
    t: Int,
    tileScaled: Int,
    x0: Int,
    y0: Int
) {
    canvas.drawImageRect(
        bitmap,
        /* The change of referential is done by offsetting coordinates by (x0, y0) */
        dstOffset = IntOffset(l - x0, t - y0),
        dstSize = IntSize(width = tileScaled, height = tileScaled),
        paint = paintPlatform.paint.asComposePaint()
    )
}
