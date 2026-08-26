package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.spec.style.RasterLayer
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.raster.RasterPaint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [RasterLayerPainter], which covers the whole tile with one image.
 *
 * The source images are built here with [renderToBitmap] rather than loaded from a fixture, so no
 * binary tile is needed -- the same reason the MVT builders in `MvtFixtures.kt` exist.
 */
class RasterLayerPainterTest {

    private fun solidImage(color: Color, size: Int = SIZE): ImageBitmap =
        renderToBitmap(size = size) { drawRect(color = color) }

    /** A [size] x [size] image split into four differently coloured quadrants. */
    private fun quadrantImage(
        topLeft: Color, topRight: Color, bottomLeft: Color, bottomRight: Color,
        size: Int = SIZE,
    ): ImageBitmap = renderToBitmap(size = size) {
        val half = size / 2f
        drawRect(color = topLeft, topLeft = Offset(0f, 0f), size = Size(half, half))
        drawRect(color = topRight, topLeft = Offset(half, 0f), size = Size(half, half))
        drawRect(color = bottomLeft, topLeft = Offset(0f, half), size = Size(half, half))
        drawRect(color = bottomRight, topLeft = Offset(half, half), size = Size(half, half))
    }

    private fun wholeTile(image: ImageBitmap) =
        RasterTileImage(image, IntOffset.Zero, IntSize(image.width, image.height))

    private suspend fun render(
        paint: RasterPaint,
        image: RasterTileImage,
    ) = renderToBitmap(size = SIZE) {
        RasterLayerPainter().paint(
            canvas = this,
            style = RasterLayer(id = "raster", paint = paint),
            image = image,
            canvasSize = SIZE,
            actualZoom = 10.0,
        )
    }

    @Test
    fun `an image with no paint properties covers the whole tile`() = runTest {
        val bitmap = render(RasterPaint(), wholeTile(solidImage(Color.Red)))

        assertColorEquals(Color.Red, bitmap.pixelAt(0, 0))
        assertColorEquals(Color.Red, bitmap.pixelAt(SIZE / 2, SIZE / 2))
        assertColorEquals(Color.Red, bitmap.pixelAt(SIZE - 1, SIZE - 1), message = "the whole tile")
    }

    @Test
    fun `a layer with no paint block at all still draws the image`() = runTest {
        val bitmap = renderToBitmap(size = SIZE) {
            RasterLayerPainter().paint(
                canvas = this,
                style = RasterLayer(id = "raster"),
                image = wholeTile(solidImage(Color.Blue)),
                canvasSize = SIZE,
                actualZoom = 10.0,
            )
        }

        assertColorEquals(Color.Blue, bitmap.pixelAt(SIZE / 2, SIZE / 2))
    }

    @Test
    fun `raster-opacity multiplies alpha only`() = runTest {
        val bitmap = render(
            RasterPaint(rasterOpacity = ExpressionOrValue.Value(0.5)),
            wholeTile(solidImage(Color.Red)),
        )

        val pixel = bitmap.pixelAt(SIZE / 2, SIZE / 2)
        assertEquals(0.5f, pixel.alpha, absoluteTolerance = 0.02f)
        assertEquals(1f, pixel.red, absoluteTolerance = 0.02f, message = "the colour itself is untouched")
    }

    @Test
    fun `a zero brightness range forces a flat colour`() = runTest {
        // brightness-min == brightness-max collapses the range, so every channel lands on it.
        val bitmap = render(
            RasterPaint(
                rasterBrightnessMin = ExpressionOrValue.Value(1.0),
                rasterBrightnessMax = ExpressionOrValue.Value(1.0),
            ),
            wholeTile(solidImage(Color.Red)),
        )

        assertColorEquals(Color.White, bitmap.pixelAt(SIZE / 2, SIZE / 2))
    }

    @Test
    fun `full negative saturation greys the image`() = runTest {
        // Red averages to a third across the three channels.
        val bitmap = render(
            RasterPaint(rasterSaturation = ExpressionOrValue.Value(-1.0)),
            wholeTile(solidImage(Color.Red)),
        )

        val pixel = bitmap.pixelAt(SIZE / 2, SIZE / 2)
        assertEquals(1f / 3f, pixel.red, absoluteTolerance = 0.02f)
        assertEquals(pixel.red, pixel.green, absoluteTolerance = 0.02f)
        assertEquals(pixel.red, pixel.blue, absoluteTolerance = 0.02f)
        assertEquals(1f, pixel.alpha, absoluteTolerance = 0.02f, message = "alpha is untouched")
    }

    @Test
    fun `a hue rotation of 120 degrees cycles the channels`() = runTest {
        val bitmap = render(
            RasterPaint(rasterHueRotate = ExpressionOrValue.Value(120.0)),
            wholeTile(solidImage(Color.Red)),
        )

        // Red in becomes green out - the shader's zxy swizzle on the green channel reads red.
        assertColorEquals(Color.Green, bitmap.pixelAt(SIZE / 2, SIZE / 2), tolerance = 0.03f)
    }

    @Test
    fun `an overzoomed tile draws only its own sub square`() = runTest {
        val image = quadrantImage(
            topLeft = Color.Red, topRight = Color.Green,
            bottomLeft = Color.Blue, bottomRight = Color.Yellow,
        )
        // One zoom level past the source's maxzoom, this map tile is the top-right quarter.
        val ref = TileRef(z = 10, x = 5, y = 4, subX = 1, subY = 0, span = 2)
        val cropped = assertNotNull(RasterTileImage.of(image, ref))

        val bitmap = render(RasterPaint(), cropped)

        assertColorEquals(Color.Green, bitmap.pixelAt(4, 4))
        assertColorEquals(Color.Green, bitmap.pixelAt(SIZE / 2, SIZE / 2))
        assertColorEquals(Color.Green, bitmap.pixelAt(SIZE - 5, SIZE - 5), message = "the whole tile")
    }

    @Test
    fun `RasterTileImage of crops to the sub square the ref names`() {
        val image = solidImage(Color.Red, size = 64)

        val whole = assertNotNull(RasterTileImage.of(image, TileRef(3, 1, 1, 0, 0, 1)))
        assertEquals(IntOffset.Zero, whole.srcOffset)
        assertEquals(IntSize(64, 64), whole.srcSize)

        val quarter = assertNotNull(RasterTileImage.of(image, TileRef(3, 1, 1, 1, 1, 2)))
        assertEquals(IntOffset(32, 32), quarter.srcOffset)
        assertEquals(IntSize(32, 32), quarter.srcSize)

        val sixteenth = assertNotNull(RasterTileImage.of(image, TileRef(3, 1, 1, 3, 2, 4)))
        assertEquals(IntOffset(48, 32), sixteenth.srcOffset)
        assertEquals(IntSize(16, 16), sixteenth.srcSize)
    }

    @Test
    fun `RasterTileImage of gives up when a sub square is narrower than a pixel`() {
        val image = solidImage(Color.Red, size = 4)
        assertEquals(null, RasterTileImage.of(image, TileRef(3, 1, 1, 5, 5, 8)))
    }

    @Test
    fun `raster-resampling nearest keeps the edge hard while linear blends it`() = runTest {
        // A two-pixel-wide image magnified 32x: the seam falls exactly at the middle of the tile.
        val source = renderToBitmap(size = 2) {
            drawRect(color = Color.Black, topLeft = Offset(0f, 0f), size = Size(1f, 2f))
            drawRect(color = Color.White, topLeft = Offset(1f, 0f), size = Size(1f, 2f))
        }

        val nearest = render(
            RasterPaint(rasterResampling = ExpressionOrValue.Value("nearest")),
            wholeTile(source),
        )
        assertColorEquals(Color.Black, nearest.pixelAt(SIZE / 2 - 1, SIZE / 2), message = "left of the seam")
        assertColorEquals(Color.White, nearest.pixelAt(SIZE / 2, SIZE / 2), message = "right of the seam")

        val linear = render(RasterPaint(), wholeTile(source))
        val blended = linear.pixelAt(SIZE / 2, SIZE / 2).red
        assertTrue(
            blended > 0.2f && blended < 0.8f,
            "the default linear resampling should blend across the seam, but the pixel was $blended",
        )
    }

    private companion object {
        const val SIZE = 64
    }
}
