package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.renderer.pixelAt
import ovh.plrapps.mapcompose.vector.renderer.renderToBitmap
import kotlin.test.Test

/**
 * The straight-alpha contract of [imageBitmapFromArgb].
 *
 * Skia's raster surfaces are premultiplied, so an actual that hands them straight ARGB has every
 * translucent pixel blended as `src.rgb + dst.rgb * (1 - a)` -- colour at full strength however
 * faint the pixel was. That is not a rounding error: it is what turned a label's soft halo edge
 * into an opaque white slab, and it saturates the hillshade and heatmap ramps the same way.
 *
 * In `skiaTest` because only the skia actuals can get this wrong -- Android's `Bitmap.setPixels`
 * takes straight ARGB by definition -- and because `ImageBitmap` needs a real graphics backend.
 */
class ImageBitmapFromArgbTest {

    private companion object {
        /** A colour whose channels are all distinct, so a swapped one shows up. */
        val TRANSLUCENT = Color(red = 0.95f, green = 0.6f, blue = 0.2f, alpha = 0.25f)
        val BACKGROUND = Color(red = 0.2f, green = 0.4f, blue = 0.8f, alpha = 1f)
    }

    private fun bitmapOf(color: Color, size: Int = 4) =
        imageBitmapFromArgb(IntArray(size * size) { color.toArgb() }, size, size)

    @Test
    fun `a translucent pixel survives the round trip`() {
        // `toPixelMap` reads back as UNPREMUL, so a bitmap holding straight bytes under a PREMUL
        // alphaType comes back divided by its own alpha -- inflated, and clamped where it saturates.
        assertColorEquals(TRANSLUCENT, bitmapOf(TRANSLUCENT).pixelAt(1, 1))
    }

    @Test
    fun `a translucent pixel composites as source-over`() {
        val rendered = renderToBitmap(size = 4, background = BACKGROUND) {
            drawImage(bitmapOf(TRANSLUCENT), topLeft = Offset.Zero)
        }

        val a = TRANSLUCENT.alpha
        val expected = Color(
            red = TRANSLUCENT.red * a + BACKGROUND.red * (1f - a),
            green = TRANSLUCENT.green * a + BACKGROUND.green * (1f - a),
            blue = TRANSLUCENT.blue * a + BACKGROUND.blue * (1f - a),
            alpha = 1f,
        )
        assertColorEquals(expected, rendered.pixelAt(1, 1))
    }
}
