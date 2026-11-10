package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.DemData
import ovh.plrapps.mapcompose.vector.data.DemUnpack
import ovh.plrapps.mapcompose.vector.data.TileRef
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_BASIC
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_COMBINED
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_IGOR
import ovh.plrapps.mapcompose.vector.spec.style.HILLSHADE_METHOD_MULTIDIRECTIONAL
import ovh.plrapps.mapcompose.vector.spec.style.HillshadeLayer
import ovh.plrapps.mapcompose.vector.spec.style.RESAMPLING_NEAREST
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.ColorArray
import ovh.plrapps.mapcompose.vector.spec.style.expression.types.NumberArray
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

    /** `Color` is a value class, so a vararg of them is prohibited -- hence the list. */
    private fun colors(values: List<Color>) = ExpressionOrValue.Value(ColorArray(values))

    private fun numbers(vararg values: Double) =
        ExpressionOrValue.Value(NumberArray(values.toList()))

    private val tintedPaint = HillshadePaint(
        hillshadeShadowColor = colors(listOf(Color.Red)),
        hillshadeHighlightColor = colors(listOf(Color.Green)),
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
        val paint = tintedPaint.copy(hillshadeIlluminationDirection = numbers(0.0))

        val bitmap = render(paint, wholeTile(ridge))

        val north = bitmap.pixelAt(SIZE / 2, SIZE / 4)
        val south = bitmap.pixelAt(SIZE / 2, 3 * SIZE / 4)

        assertTrue(north.green > north.red, "the north-facing flank takes the highlight ($north)")
        assertTrue(south.red > south.green, "the south-facing flank takes the shadow ($south)")
    }

    @Test
    fun `turning the light around swaps which flank is lit`() = runTest {
        val ridge = dem { _, y -> if (y < DEM / 2) y * 8 else (DEM - 1 - y) * 8 }
        val fromSouth = tintedPaint.copy(hillshadeIlluminationDirection = numbers(180.0))

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
        /* Away from the boundary, where the eastern sub-square's window reaches back across it for
         * the half sample bilinear filtering needs -- which is what keeps the two sub-squares
         * continuous, and is what upstream's single texture does for free. */
        for (x in SIZE / 4 until SIZE) {
            assertEquals(
                0f,
                right.pixelAt(x, SIZE / 2).alpha,
                "the eastern sub-square is flat ground at x=$x",
            )
        }
    }

    @Test
    fun `a source overzoomed past its own sample count still shades`() = runTest {
        // Two levels past a 4-sample DEM: each map tile is a quarter of one sample, so dividing the
        // DEM's dimension by the span reaches zero -- which used to erase the layer.
        val small = dem(dim = 4) { x, _ -> x * 85 }

        val bitmap = render(
            tintedPaint,
            DemTile(small, TileRef(z = 20, x = 0, y = 0, subX = 7, subY = 7, span = 16)),
            tileZ = 20,
        )

        assertTrue(
            bitmap.opaquePixelCount() > 0,
            "a DEM magnified past one sample per tile must still shade",
        )
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

    /**
     * A tile at z16 rather than the z12 the tests above use.
     *
     * The slope divisor is proportional to the ground resolution, so at z12 a DEM step of a few
     * metres per sample produces a derivative around 0.04 -- every method then shades so faintly
     * that two of them round to the same 8-bit pixel. These comparisons need the algorithms to be
     * telling themselves apart, not the quantizer.
     */
    private fun steepTile() = wholeTile(dem { x, y -> x * 6 + y * 3 }, z = STEEP_Z)

    private suspend fun renderSteep(paint: HillshadePaint) =
        render(paint, steepTile(), tileZ = STEEP_Z, tileY = 1 shl (STEEP_Z - 1))

    private fun ImageBitmap.differsFrom(other: ImageBitmap): Boolean =
        (0 until SIZE).any { y -> (0 until SIZE).any { x -> pixelAt(x, y) != other.pixelAt(x, y) } }

    @Test
    fun `a method other than standard shades differently`() = runTest {
        val standard = renderSteep(tintedPaint)

        for (method in listOf(
            HILLSHADE_METHOD_BASIC,
            HILLSHADE_METHOD_COMBINED,
            HILLSHADE_METHOD_IGOR,
            HILLSHADE_METHOD_MULTIDIRECTIONAL,
        )) {
            val other = renderSteep(
                tintedPaint.copy(hillshadeMethod = ExpressionOrValue.Value(method))
            )
            assertTrue(
                standard.differsFrom(other),
                "$method rendered as standard -- hillshade-method was dropped",
            )
        }
    }

    @Test
    fun `the light altitude drives the methods that read it`() = runTest {
        val basic = tintedPaint.copy(
            hillshadeMethod = ExpressionOrValue.Value(HILLSHADE_METHOD_BASIC)
        )

        val low = renderSteep(basic.copy(hillshadeIlluminationAltitude = numbers(10.0)))
        val high = renderSteep(basic.copy(hillshadeIlluminationAltitude = numbers(80.0)))

        assertTrue(
            low.differsFrom(high),
            "hillshade-illumination-altitude must reach basic_hillshade",
        )
    }

    @Test
    fun `a multidirectional layer averages every source it declares`() = runTest {
        val multi = tintedPaint.copy(
            hillshadeMethod = ExpressionOrValue.Value(HILLSHADE_METHOD_MULTIDIRECTIONAL),
            hillshadeIlluminationAltitude = numbers(45.0),
        )

        val one = renderSteep(multi.copy(hillshadeIlluminationDirection = numbers(90.0)))
        val four = renderSteep(
            multi.copy(hillshadeIlluminationDirection = numbers(90.0, 180.0, 270.0, 0.0))
        )

        assertTrue(
            one.differsFrom(four),
            "each declared direction must contribute a shading pass",
        )
    }

    @Test
    fun `nearest resampling keeps the dem's hard edges`() = runTest {
        // A one-sample step, magnified onto a tile twice the DEM's size: bilinear smears the seam.
        val step = wholeTile(dem { x, _ -> if (x < DEM / 2) 0 else 120 }, z = STEEP_Z)

        val linear = render(tintedPaint, step, tileZ = STEEP_Z, tileY = 1 shl (STEEP_Z - 1))
        val nearest = render(
            tintedPaint.copy(resampling = ExpressionOrValue.Value(RESAMPLING_NEAREST)),
            step,
            tileZ = STEEP_Z,
            tileY = 1 shl (STEEP_Z - 1),
        )

        assertTrue(linear.differsFrom(nearest), "resampling must reach the tile blit")
    }

    private companion object {
        const val SIZE = 64
        const val DEM = 32
        const val STEEP_Z = 16
    }
}
