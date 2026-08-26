package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.fill.FillPaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [FillLayerPainter].
 *
 * The tile is 64 x 64 at density 1 and the features use an extent of 4096, so a tile coordinate
 * maps to canvas pixels by dividing by 64: the ring `(0,0)-(2048,2048)` covers the top-left
 * quadrant.
 */
class FillLayerPainterTest {

    private val painter = FillLayerPainter()

    private fun fillLayer(paint: FillPaint) = FillLayer(id = "fill", sourceLayer = "test", paint = paint)

    private suspend fun render(paint: FillPaint, feature: ovh.plrapps.mapcompose.vector.spec.Tile.Feature) =
        renderToBitmap(size = SIZE) {
            painter.paint(
                canvas = this,
                feature = feature,
                style = fillLayer(paint),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

    @Test
    fun `a fill with no colour uses the spec default black`() = runTest {
        val bitmap = render(FillPaint(), quadrant())

        assertColorEquals(Color.Black, bitmap.pixelAt(16, 16), message = "inside the ring")
        assertEquals(0f, bitmap.pixelAt(48, 48).alpha, "outside the ring")
    }

    @Test
    fun `fill-color is honoured`() = runTest {
        val bitmap = render(FillPaint(fillColor = ExpressionOrValue.Value(Color.Red)), quadrant())

        assertColorEquals(Color.Red, bitmap.pixelAt(16, 16))
    }

    @Test
    fun `fill-opacity multiplies the colour alpha`() = runTest {
        val bitmap = render(
            FillPaint(
                fillColor = ExpressionOrValue.Value(Color.Red),
                fillOpacity = ExpressionOrValue.Value(0.5),
            ),
            quadrant(),
        )

        assertEquals(0.5f, bitmap.pixelAt(16, 16).alpha, absoluteTolerance = 0.02f)
    }

    @Test
    fun `no outline is drawn when the style sets no outline colour`() = runTest {
        // Divergence from upstream: it would stroke a same-coloured hairline here as its
        // antialiasing pass. drawPath is antialiased already, so that would only fatten the polygon
        // by half a pixel on each side -- the two renders must be identical.
        val paint = FillPaint(fillColor = ExpressionOrValue.Value(Color.Red))
        val antialiased = render(paint, quadrant())
        val plain = render(paint.copy(fillAntialias = ExpressionOrValue.Value(false)), quadrant())

        assertEquals(
            plain.opaquePixelCount(),
            antialiased.opaquePixelCount(),
            "fill-antialias must not grow the polygon when no outline colour is set"
        )
    }

    @Test
    fun `fill-outline-color is drawn only when fill-antialias is on`() = runTest {
        val paint = FillPaint(
            fillColor = ExpressionOrValue.Value(Color.Transparent),
            fillOutlineColor = ExpressionOrValue.Value(Color.Red),
        )

        val outlined = render(paint, quadrant())
        val plain = render(paint.copy(fillAntialias = ExpressionOrValue.Value(false)), quadrant())

        assertTrue(outlined.opaquePixelCount() > 0, "the outline should be visible")
        assertEquals(0, plain.opaquePixelCount(), "fill-antialias false suppresses the outline")
    }

    @Test
    fun `fill-translate shifts the feature`() = runTest {
        val paint = FillPaint(
            fillColor = ExpressionOrValue.Value(Color.Red),
            fillTranslate = ExpressionOrValue.Value(listOf(16.0, 16.0)),
        )

        val bitmap = render(paint, quadrant())

        assertEquals(0f, bitmap.pixelAt(4, 4).alpha, "the original top-left corner is now empty")
        assertColorEquals(Color.Red, bitmap.pixelAt(40, 40), message = "shifted by 16 px")
    }

    @Test
    fun `a hole is cut out of the fill`() = runTest {
        val feature = Mvt.polygonFeature(
            Mvt.clockwiseRing(0, 0, 4096, 4096),
            Mvt.counterClockwiseRing(1024, 1024, 3072, 3072),
        )

        val bitmap = render(FillPaint(fillColor = ExpressionOrValue.Value(Color.Red)), feature)

        assertColorEquals(Color.Red, bitmap.pixelAt(4, 4), message = "outside the hole")
        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "inside the hole")
    }

    @Test
    fun `a multipolygon draws every part`() = runTest {
        val feature = Mvt.polygonFeature(
            Mvt.clockwiseRing(0, 0, 1024, 1024),
            Mvt.clockwiseRing(3072, 3072, 4096, 4096),
        )

        val bitmap = render(FillPaint(fillColor = ExpressionOrValue.Value(Color.Red)), feature)

        assertColorEquals(Color.Red, bitmap.pixelAt(8, 8), message = "first part")
        assertColorEquals(Color.Red, bitmap.pixelAt(56, 56), message = "second part")
        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "the gap between them")
    }

    @Test
    fun `an unresolvable fill-pattern falls back to fill-color instead of dropping the feature`() = runTest {
        val paint = FillPaint(
            fillColor = ExpressionOrValue.Value(Color.Red),
            fillPattern = ExpressionOrValue.Value("no-such-sprite"),
        )

        val bitmap = render(paint, quadrant())

        assertColorEquals(Color.Red, bitmap.pixelAt(16, 16))
    }

    @Test
    fun `line and point features are ignored`() = runTest {
        val line = render(
            FillPaint(fillColor = ExpressionOrValue.Value(Color.Red)),
            Mvt.lineFeature(listOf(0 to 0, 4096 to 4096)),
        )
        val point = render(
            FillPaint(fillColor = ExpressionOrValue.Value(Color.Red)),
            Mvt.pointFeature(2048 to 2048),
        )

        assertEquals(0, line.opaquePixelCount())
        assertEquals(0, point.opaquePixelCount())
    }

    @Test
    fun `fill-pattern paints the sprite`() = runTest {
        val sprites = spriteSheet("dots", Color.Green)
        val patterned = FillLayerPainter(spriteManager = sprites)

        val bitmap = renderToBitmap(size = SIZE) {
            patterned.paint(
                canvas = this,
                feature = quadrant(),
                style = fillLayer(
                    FillPaint(
                        fillColor = ExpressionOrValue.Value(Color.Red),
                        fillPattern = ExpressionOrValue.Value("dots"),
                    )
                ),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

        assertColorEquals(Color.Green, bitmap.pixelAt(16, 16), message = "the pattern, not fill-color")
    }

    private fun quadrant() = Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 2048, 2048))

    private companion object {
        const val SIZE = 64
    }
}
