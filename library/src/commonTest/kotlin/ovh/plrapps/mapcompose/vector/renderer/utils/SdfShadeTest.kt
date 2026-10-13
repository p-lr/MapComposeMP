package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SdfShade] and [sourceOverArgb] are the label rasterizer's per-pixel arithmetic without the
 * `Color`s; they must agree with [sdfPixel] and with compositing `Color`s bit for bit, or every
 * label would shift by a rounding step somewhere.
 */
class SdfShadeTest {

    private val fills = listOf(Color.Black, Color(0xFF336699), Color(0x80FF0000), Color(0xFFFFFFFF))
    private val halos = listOf(Color.Transparent, Color.White, Color(0xC0102030), Color(0x40FFFF00))
    private val distances = (0..400).map { it / 400f }

    @Test
    fun `the shade equals sdfPixel bit for bit`() {
        for (fill in fills) for (halo in halos) {
            for ((haloWidth, haloBlur, fontScale) in listOf(
                Triple(0f, 0f, 1f), Triple(1.5f, 0.5f, 0.75f), Triple(3f, 2f, 1.25f), Triple(0.5f, 0f, 2f),
            )) {
                val shade = SdfShade(fill, halo, haloWidth, haloBlur, fontScale)
                for (distance in distances) {
                    val expected = sdfPixel(distance, fill, halo, haloWidth, haloBlur, fontScale)
                    val actual = shade.argb(distance)
                    if (expected.alpha <= 0f) {
                        assertEquals(0, actual ushr 24, "transparent at $distance for $fill/$halo")
                    } else {
                        assertEquals(expected.toArgb(), actual, "at $distance for $fill/$halo")
                    }
                }
            }
        }
    }

    @Test
    fun `source over equals compositing Colors bit for bit`() {
        val samples = listOf(0, 0x01000000, 0x7F123456, 0x80FF00FF.toInt(), 0xFFABCDEF.toInt(), 0x20FFFFFF, 0x05000000)
        for (source in samples) for (destination in samples) {
            assertEquals(reference(Color(source), destination), sourceOverArgb(source, destination), "$source over $destination")
        }
    }

    /** The `Color`-based source-over the rasterizer used before. */
    private fun reference(source: Color, destinationArgb: Int): Int {
        if (source.alpha >= 1f) return source.toArgb()
        val destination = Color(destinationArgb)
        val behind = destination.alpha * (1f - source.alpha)
        val alpha = source.alpha + behind
        if (alpha <= 0f) return 0
        return Color(
            red = (source.red * source.alpha + destination.red * behind) / alpha,
            green = (source.green * source.alpha + destination.green * behind) / alpha,
            blue = (source.blue * source.alpha + destination.blue * behind) / alpha,
            alpha = alpha,
        ).toArgb()
    }
}
