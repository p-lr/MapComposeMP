package ovh.plrapps.mapcompose.ui.view

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.SamplingMode

actual class PaintPlatform(
    val paint: Paint,
    var samplingMode: SamplingMode = SamplingMode.LINEAR
)

actual fun makePaintPlatform(): PaintPlatform {
    return PaintPlatform(
        Paint().apply { isAntiAlias = false }
    )
}

actual fun updateFilterBitmap(paintPlatform: PaintPlatform, isFilteringBitmap: () -> Boolean) {
    paintPlatform.samplingMode =
        if (isFilteringBitmap()) SamplingMode.LINEAR else SamplingMode.DEFAULT
}

actual fun setTilePaintProperties(
    paintPlatform: PaintPlatform,
    alpha: Float,
    colorFilter: ColorFilter?
) {
    paintPlatform.paint.apply {
        this.alpha = alpha
        this.colorFilter = colorFilter
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
        paint = paintPlatform.paint
    )
}
