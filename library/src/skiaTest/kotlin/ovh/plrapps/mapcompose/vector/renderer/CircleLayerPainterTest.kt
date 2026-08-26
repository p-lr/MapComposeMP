package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.CircleLayer
import ovh.plrapps.mapcompose.vector.spec.style.circle.CirclePaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [CircleLayerPainter].
 *
 * The tile is 64 x 64 at density 1 with an extent of 4096, so the point `(2048, 2048)` sits at the
 * centre pixel `(32, 32)`.
 */
class CircleLayerPainterTest {

    private val painter = CircleLayerPainter()

    private suspend fun render(paint: CirclePaint, feature: Tile.Feature = centrePoint()) =
        renderToBitmap(size = SIZE) {
            painter.paint(
                canvas = this,
                feature = feature,
                style = CircleLayer(id = "circle", sourceLayer = "test", paint = paint),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

    @Test
    fun `a point feature is drawn at all`() = runTest {
        // Regression: the painter used to build a Path, which is null for point geometry, so a
        // circle layer drew nothing whatsoever.
        val bitmap = render(CirclePaint())

        assertTrue(bitmap.opaquePixelCount() > 0, "a circle layer must draw its points")
    }

    @Test
    fun `a circle with no colour uses the spec defaults`() = runTest {
        val bitmap = render(CirclePaint())

        assertColorEquals(Color.Black, bitmap.pixelAt(32, 32), message = "default circle-color")
        // The default radius is 5 px, so 10 px out is clear and 3 px out is not.
        assertColorEquals(Color.Black, bitmap.pixelAt(32, 29))
        assertEquals(0f, bitmap.pixelAt(32, 42).alpha, "beyond the default radius")
    }

    @Test
    fun `circle-radius is honoured`() = runTest {
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(20.0),
                circleColor = red,
            )
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 15), message = "17 px from the centre")
        assertEquals(0f, bitmap.pixelAt(32, 8).alpha, "24 px from the centre is outside")
    }

    @Test
    fun `circle-opacity multiplies the colour alpha`() = runTest {
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(16.0),
                circleColor = red,
                circleOpacity = ExpressionOrValue.Value(0.25),
            )
        )

        assertEquals(0.25f, bitmap.pixelAt(32, 32).alpha, absoluteTolerance = 0.02f)
    }

    @Test
    fun `circle-stroke is drawn outside the fill`() = runTest {
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(10.0),
                circleColor = red,
                circleStrokeWidth = ExpressionOrValue.Value(6.0),
                circleStrokeColor = ExpressionOrValue.Value(Color.Blue),
            )
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32), message = "the fill")
        assertColorEquals(Color.Blue, bitmap.pixelAt(32, 20), message = "12 px out is in the stroke")
        assertEquals(0f, bitmap.pixelAt(32, 15).alpha, "17 px out is past the stroke")
    }

    @Test
    fun `circle-stroke-opacity is independent of circle-opacity`() = runTest {
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(10.0),
                circleColor = red,
                circleOpacity = ExpressionOrValue.Value(1.0),
                circleStrokeWidth = ExpressionOrValue.Value(6.0),
                circleStrokeColor = ExpressionOrValue.Value(Color.Blue),
                circleStrokeOpacity = ExpressionOrValue.Value(0.5),
            )
        )

        assertEquals(1f, bitmap.pixelAt(32, 32).alpha, absoluteTolerance = 0.02f, message = "the fill")
        assertEquals(0.5f, bitmap.pixelAt(32, 20).alpha, absoluteTolerance = 0.03f, message = "the stroke")
    }

    @Test
    fun `circle-blur fades the disc towards its edge`() = runTest {
        val sharp = render(CirclePaint(circleRadius = ExpressionOrValue.Value(20.0), circleColor = red))
        val blurred = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(20.0),
                circleColor = red,
                circleBlur = ExpressionOrValue.Value(1.0),
            )
        )

        assertColorEquals(Color.Red, blurred.pixelAt(32, 32), message = "the centre stays opaque")
        assertEquals(1f, sharp.pixelAt(32, 18).alpha, absoluteTolerance = 0.02f, message = "no blur, no fade")
        val faded = blurred.pixelAt(32, 18).alpha
        assertTrue(faded > 0f && faded < 0.9f, "the edge should be partly transparent, was $faded")
    }

    @Test
    fun `circle-translate shifts the circle`() = runTest {
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(6.0),
                circleColor = red,
                circleTranslate = ExpressionOrValue.Value(listOf(-20.0, 0.0)),
            )
        )

        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "no longer at the original point")
        assertColorEquals(Color.Red, bitmap.pixelAt(12, 32), message = "shifted 20 px left")
    }

    @Test
    fun `every point of a multipoint feature gets a circle`() = runTest {
        val bitmap = render(
            CirclePaint(circleRadius = ExpressionOrValue.Value(6.0), circleColor = red),
            feature = Mvt.pointFeature(1024 to 1024, 3072 to 3072),
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(16, 16))
        assertColorEquals(Color.Red, bitmap.pixelAt(48, 48))
    }

    @Test
    fun `line and polygon features are ignored`() = runTest {
        val line = render(CirclePaint(circleColor = red), Mvt.lineFeature(listOf(0 to 0, 4096 to 4096)))
        val polygon = render(CirclePaint(circleColor = red), Mvt.polygonFeature(Mvt.clockwiseRing(0, 0, 4096, 4096)))

        assertEquals(0, line.opaquePixelCount())
        assertEquals(0, polygon.opaquePixelCount())
    }

    private fun centrePoint() = Mvt.pointFeature(2048 to 2048)

    private companion object {
        const val SIZE = 64
        val red = ExpressionOrValue.Value(Color.Red)
    }
}
