package ovh.plrapps.mapcompose.ui.view

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.nativeCanvas


actual class PaintPlatform(val paint: Paint)

actual fun makePaintPlatform(): PaintPlatform {
    return PaintPlatform(
        Paint().apply { isAntiAlias = false }
    )
}

actual fun updateFilterBitmap(paintPlatform: PaintPlatform, isFilteringBitmap: () -> Boolean) {
    paintPlatform.paint.isFilterBitmap = isFilteringBitmap()
}

actual fun setTilePaintProperties(
    paintPlatform: PaintPlatform,
    alpha: Int,
    colorFilter: ColorFilter?
) {
    paintPlatform.paint.apply {
        this.alpha = alpha
        this.colorFilter = colorFilter?.asAndroidColorFilter()
    }
}

val dest = Rect()
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
    val r = l + tileScaled
    val b = t + tileScaled

    /* The change of referential is done by offsetting coordinates by (x0, y0) */
    dest.set(l - x0, t - y0, r - x0, b - y0)

    canvas.nativeCanvas.drawBitmap(bitmap.asAndroidBitmap(), null, dest, paintPlatform.paint)
}
