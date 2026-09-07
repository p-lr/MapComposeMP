package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.DemUnpack
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.ColorReliefLayer
import ovh.plrapps.mapcompose.vector.spec.style.colorRelief.ColorReliefPaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [ColorReliefLayerPainter].
 *
 * The DEMs are built from plain integers with an encoding whose blue channel is metres, exactly as
 * `HillshadeLayerPainterTest` does -- the unpack step is `DemDataTest`'s subject, and the ramp
 * arithmetic is `ColorReliefRampTest`'s, so what is left here is what reaches the tile bitmap.
 */
class ColorReliefLayerPainterTest {

    /** One elevation per sample, encoded so that the blue channel is metres. */
    private val metresPerBlue = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)

    private fun dem(dim: Int = DEM, elevation: (x: Int, y: Int) -> Int): DemData {
        val pixels = IntArray(dim * dim) { i ->
            (0xFF shl 24) or elevation(i % dim, i / dim).coerceIn(0, 255)
        }
        return assertNotNull(DemData.fromArgb(pixels, dim, dim, metresPerBlue))
    }

    private fun wholeTile(dem: DemData) =
        DemTile(dem, TileRef(z = 12, x = 0, y = 0, subX = 0, subY = 0, span = 1))

    /** Black at sea level through to white at 255 m, which is the DEM encoding's whole range. */
    private val blackToWhite = ColorReliefPaint(
        colorReliefColor = json.decodeFromString(
            """["interpolate",["linear"],["elevation"],0,"#000000",255,"#ffffff"]"""
        )
    )

    private suspend fun render(paint: ColorReliefPaint, demTile: DemTile) =
        renderToBitmap(size = SIZE) {
            ColorReliefLayerPainter().paint(
                canvas = this,
                style = ColorReliefLayer(id = "relief", paint = paint),
                demTile = demTile,
                canvasSize = SIZE,
                actualZoom = 12.0,
            )
        }

    @Test
    fun `a flat tile is one colour off the ramp`() = runTest {
        val bitmap = render(blackToWhite, wholeTile(dem { _, _ -> 128 }))

        assertColorEquals(
            expected = Color(128 / 255f, 128 / 255f, 128 / 255f),
            actual = bitmap.pixelAt(SIZE / 2, SIZE / 2),
            message = "128 m is halfway along a 0..255 m ramp",
        )
    }

    @Test
    fun `elevation drives the colour across the tile`() = runTest {
        // A west-east elevation gradient over the DEM's full range.
        val slope = dem { x, _ -> x * 255 / (DEM - 1) }

        val bitmap = render(blackToWhite, wholeTile(slope))

        val west = bitmap.pixelAt(1, SIZE / 2)
        val east = bitmap.pixelAt(SIZE - 2, SIZE / 2)
        assertTrue(west.red < 0.1f, "the low end of the ramp is black ($west)")
        assertTrue(east.red > 0.9f, "the high end is white ($east)")
    }

    @Test
    fun `a property that is not an interpolate draws nothing`() = runTest {
        // Upstream's empty ramp: one transparent stop, so the layer is invisible.
        val stepped = ColorReliefPaint(
            colorReliefColor = json.decodeFromString(
                """["step",["elevation"],"#000000",100,"#ffffff"]"""
            )
        )

        val bitmap = render(stepped, wholeTile(dem { _, _ -> 128 }))

        assertEquals(0, bitmap.opaquePixelCount(), "there is no ramp to draw through")
    }

    @Test
    fun `an absent color-relief-color draws nothing`() = runTest {
        val bitmap = render(ColorReliefPaint(), wholeTile(dem { _, _ -> 128 }))

        assertEquals(0, bitmap.opaquePixelCount(), "the property has no spec default")
    }

    @Test
    fun `color-relief-opacity scales the whole layer`() = runTest {
        val half = blackToWhite.copy(colorReliefOpacity = ExpressionOrValue.Value(0.5))

        val bitmap = render(half, wholeTile(dem { _, _ -> 255 }))
        val pixel = bitmap.pixelAt(SIZE / 2, SIZE / 2)

        assertColorEquals(Color(1f, 1f, 1f, 0.5f), pixel, message = "white at half alpha")
    }

    @Test
    fun `zero opacity draws nothing`() = runTest {
        val invisible = blackToWhite.copy(colorReliefOpacity = ExpressionOrValue.Value(0.0))

        val bitmap = render(invisible, wholeTile(dem { _, _ -> 128 }))

        assertEquals(0, bitmap.opaquePixelCount())
    }

    @Test
    fun `an overzoomed tile colours only its own sub-square`() = runTest {
        // Sea level in the left half of the DEM, the ramp's top in the right half.
        val split = dem { x, _ -> if (x < DEM / 2) 0 else 255 }

        val left = render(
            blackToWhite,
            DemTile(split, TileRef(z = 12, x = 0, y = 0, subX = 0, subY = 0, span = 2)),
        )
        val right = render(
            blackToWhite,
            DemTile(split, TileRef(z = 12, x = 0, y = 0, subX = 1, subY = 0, span = 2)),
        )

        assertTrue(
            left.pixelAt(SIZE / 2, SIZE / 2).red < 0.1f,
            "the west sub-square magnifies the DEM's low half",
        )
        assertTrue(
            right.pixelAt(SIZE / 2, SIZE / 2).red > 0.9f,
            "the east sub-square magnifies its high half",
        )
    }

    private companion object {
        const val SIZE = 64
        const val DEM = 32
    }
}
