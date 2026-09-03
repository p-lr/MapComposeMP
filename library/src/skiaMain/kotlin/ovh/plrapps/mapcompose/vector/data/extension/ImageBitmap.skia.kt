package ovh.plrapps.mapcompose.vector.data.extension

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Reading into a `BGRA_8888` / `UNPREMUL` [ImageInfo] is what makes this one copy and no per-pixel
 * work: those are already BMP's channel order and BMP's straight alpha. Skia converts on the way
 * out, which is the whole point -- a tile bitmap is `PREMUL`, and handing its own bytes over as if
 * they were straight alpha saturates every translucent pixel. See [encodeBmp32FromBgra].
 */
actual fun ImageBitmap.toBytes(): ByteArray? {
    val info = ImageInfo(
        width = width,
        height = height,
        colorType = ColorType.BGRA_8888,
        alphaType = ColorAlphaType.UNPREMUL
    )

    val bgra = asSkiaBitmap().readPixels(info, width * 4, 0, 0) ?: return null

    return encodeBmp32FromBgra(bgra, width, height)
}
