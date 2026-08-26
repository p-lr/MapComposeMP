package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.DemUnpack
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.spec.style.HillshadeLayer
import ovh.plrapps.mapcompose.vector.spec.style.hillshade.HillshadePaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [HillshadeLayerPainter].
 *
 * The DEMs are built here from plain integers rather than loaded from a Terrain-RGB fixture: the
 * unpack step is covered by `DemDataTest`, so an encoding where the blue channel is simply metres
 * keeps these tests about the shading.
 *
 * Shadow and highlight are tinted red and green so that "which side is lit" is a channel comparison
 * rather than a brightness one, which survives the tile bitmap's bilinear scaling.
 */
class HillshadeLayerPainterTest {

    /** One elevation per sample, encoded so that the blue channel is metres. */
    private val metresPerBlue = DemUnpack(red = 0.0, green = 0.0, blue = 1.0, baseShift = 0.0)

    private fun dem(dim: Int = DEM, elevation: (x: Int, y: Int) -> Int): DemData {
        val pixels = IntArray(dim * dim) { i ->
            (0xFF shl 24) or elevation(i % dim, i / dim).coerceIn(0, 255)
        }
        return assertNotNull(DemData.fromArgb(pixels, dim, dim, metresPerBlue))
    }

    private fun wholeTile(dem: DemData, z: Int = 12) =
        DemTile(dem, TileRef(z = z, x = 0, y = 0, subX = 0, subY = 0, span = 1))

    private val tintedPaint = HillshadePaint(
        hillshadeShadowColor = ExpressionOrValue.Value(Color.Red),
        hillshadeHighlightColor = ExpressionOrValue.Value(Color.Green),
        hillshadeAccentColor = ExpressionOrValue.Value(Color.Blue),
    )

    private suspend fun render(
        paint: HillshadePaint,
        demTile: DemTile,
        tileZ: Int = 12,
        tileY: Int = 2048,
    ) = renderToBitmap(size = SIZE) {
        HillshadeLayerPainter().paint(
            canvas = this,
            style = HillshadeLayer(id = "hillshade", paint = paint),
            demTile = demTile,
            canvasSize = SIZE,
            tileZ = tileZ,
            tileY = tileY,
            actualZoom = tileZ.toDouble(),
        )
    }

    @Test
    fun `flat terrain is not shaded at all`() = runTest {
        val bitmap = render(HillshadePaint(), wholeTile(dem { _, _ -> 100 }))

        assertEquals(0, bitmap.opaquePixelCount(), "there is no slope to light")
    }

    @Test
    fun `a layer with no paint block at all still shades`() = runTest {
        val bitmap = renderToBitmap(size = SIZE) {
            HillshadeLayerPainter().paint(
                canvas = this,
                style = HillshadeLayer(id = "hillshade"),
                demTile = wholeTile(dem { x, _ -> x * 8 }),
                canvasSize = SIZE,
                tileZ = 12,
                tileY = 2048,
                actualZoom = 12.0,
            )
        }

        assertTrue(bitmap.opaquePixelCount() > 0, "the spec defaults must supply a usable light")
    }

    @Test
    fun `zero exaggeration draws nothing`() = runTest {
        val bitmap = render(
            HillshadePaint(hillshadeExaggeration = ExpressionOrValue.Value(0.0)),
            wholeTile(dem { x, _ -> x * 8 }),
        )

        assertEquals(0, bitmap.opaquePixelCount(), "upstream skips the layer's offscreen pass")
    }

    @Test
    fun `a north facing slope is lit and a south facing one is shadowed`() = runTest {
        // A ridge running east-west: elevation peaks in the middle row, so the top half faces north
        // and the bottom half faces south.
        val ridge = dem { _, y -> if (y < DEM / 2) y * 8 else (DEM - 1 - y) * 8 }
        // Light from due north.
        val paint = tintedPaint.copy(hillshadeIlluminationDirection = ExpressionOrValue.Value(0.0))

        val bitmap = render(paint, wholeTile(ridge))

        val north = bitmap.pixelAt(SIZE / 2, SIZE / 4)
        val south = bitmap.pixelAt(SIZE / 2, 3 * SIZE / 4)

        assertTrue(north.green > north.red, "the north-facing flank takes the highlight ($north)")
        assertTrue(south.red > south.green, "the south-facing flank takes the shadow ($south)")
    }

    @Test
    fun `turning the light around swaps which flank is lit`() = runTest {
        val ridge = dem { _, y -> if (y < DEM / 2) y * 8 else (DEM - 1 - y) * 8 }
        val fromSouth = tintedPaint.copy(
            hillshadeIlluminationDirection = ExpressionOrValue.Value(180.0)
        )

        val bitmap = render(fromSouth, wholeTile(ridge))

        val north = bitmap.pixelAt(SIZE / 2, SIZE / 4)
        val south = bitmap.pixelAt(SIZE / 2, 3 * SIZE / 4)

        assertTrue(north.red > north.green, "now the north flank is in shadow ($north)")
        assertTrue(south.green > south.red, "and the south flank is lit ($south)")
    }

    @Test
    fun `an overzoomed tile shades only its own sub-square`() = runTest {
        // Slope in the left half of the DEM only; the right half is flat.
        val halfSloped = dem { x, _ -> if (x < DEM / 2) x * 8 else (DEM / 2 - 1) * 8 }

        val left = render(
            tintedPaint,
            DemTile(halfSloped, TileRef(z = 12, x = 0, y = 0, subX = 0, subY = 0, span = 2)),
        )
        val right = render(
            tintedPaint,
            DemTile(halfSloped, TileRef(z = 12, x = 1, y = 0, subX = 1, subY = 0, span = 2)),
        )

        assertTrue(left.opaquePixelCount() > 0, "the western sub-square holds the slope")
        assertEquals(0, right.opaquePixelCount(), "the eastern one is flat ground")
    }

    @Test
    fun `a steeper slope shades more strongly`() = runTest {
        val gentle = render(tintedPaint, wholeTile(dem { x, _ -> x * 2 }))
        val steep = render(tintedPaint, wholeTile(dem { x, _ -> x * 6 }))

        val gentleAlpha = gentle.pixelAt(SIZE / 2, SIZE / 2).alpha
        val steepAlpha = steep.pixelAt(SIZE / 2, SIZE / 2).alpha

        assertTrue(
            steepAlpha > gentleAlpha,
            "expected the steeper ramp to shade harder but got $steepAlpha against $gentleAlpha",
        )
    }

    @Test
    fun `the same slope shades harder nearer the pole`() = runTest {
        // Mercator stretches north-south, so a slope near a pole covers fewer real metres per pixel
        // than the same pixel run at the equator -- upstream divides by cos(latitude) to undo it.
        val ramp = dem { _, y -> y * 4 }

        val equator = render(tintedPaint, wholeTile(ramp), tileZ = 12, tileY = 2048)
        val far = render(tintedPaint, wholeTile(ramp), tileZ = 12, tileY = 40)

        val equatorAlpha = equator.pixelAt(SIZE / 2, SIZE / 2).alpha
        val farAlpha = far.pixelAt(SIZE / 2, SIZE / 2).alpha

        assertTrue(
            farAlpha > equatorAlpha,
            "expected more shading at high latitude but got $farAlpha against $equatorAlpha",
        )
    }

    private companion object {
        const val SIZE = 64
        const val DEM = 32
    }
}
