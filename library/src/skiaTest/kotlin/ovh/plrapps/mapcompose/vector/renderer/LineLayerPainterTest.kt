package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.line.LineLayout
import ovh.plrapps.mapcompose.vector.spec.style.line.LinePaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixel-level tests for [LineLayerPainter].
 *
 * The tile is 64 x 64 at density 1 with an extent of 4096, so a tile coordinate maps to canvas
 * pixels by dividing by 64. Most features are a horizontal line at y = 32.
 */
class LineLayerPainterTest {

    private val painter = LineLayerPainter()

    private suspend fun render(
        paint: LinePaint,
        layout: LineLayout? = null,
        feature: Tile.Feature = horizontalLine(),
    ) = renderToBitmap(size = SIZE) {
        painter.paint(
            canvas = this,
            feature = feature,
            style = LineLayer(id = "line", sourceLayer = "test", paint = paint, layout = layout),
            canvasSize = SIZE,
            extent = Mvt.DEFAULT_EXTENT,
            zoom = 10.0,
            featureProperties = null,
            actualZoom = 10.0,
        )
    }

    @Test
    fun `a line with no colour uses the spec default black`() = runTest {
        val bitmap = render(LinePaint(lineWidth = ExpressionOrValue.Value(4.0)))

        assertColorEquals(Color.Black, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `line-width controls the stroke thickness`() = runTest {
        val thin = render(LinePaint(lineWidth = ExpressionOrValue.Value(2.0), lineColor = red))
        val thick = render(LinePaint(lineWidth = ExpressionOrValue.Value(10.0), lineColor = red))

        assertEquals(0f, thin.pixelAt(32, 26).alpha, "6 px above a 2 px line is clear")
        assertColorEquals(Color.Red, thick.pixelAt(32, 30), message = "a 10 px line reaches y = 30")
    }

    @Test
    fun `line-opacity multiplies the colour alpha`() = runTest {
        val bitmap = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(8.0),
                lineColor = red,
                lineOpacity = ExpressionOrValue.Value(0.5),
            )
        )

        assertEquals(0.5f, bitmap.pixelAt(32, 32).alpha, absoluteTolerance = 0.02f)
    }

    @Test
    fun `line-offset shifts the line perpendicular to its direction`() = runTest {
        val bitmap = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineOffset = ExpressionOrValue.Value(12.0),
            )
        )

        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "the line has moved off its original row")
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 44), message = "offset by 12 px")
    }

    @Test
    fun `line-translate shifts the line`() = runTest {
        val bitmap = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineTranslate = ExpressionOrValue.Value(listOf(0.0, -16.0)),
            )
        )

        assertEquals(0f, bitmap.pixelAt(32, 32).alpha)
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 16), message = "translated 16 px up")
    }

    @Test
    fun `line-gap-width splits the line into a casing`() = runTest {
        val bitmap = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineGapWidth = ExpressionOrValue.Value(12.0),
            )
        )

        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "the middle is the gap")
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 24), message = "upper casing")
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 40), message = "lower casing")
    }

    @Test
    fun `line-dasharray leaves gaps`() = runTest {
        val solid = render(LinePaint(lineWidth = ExpressionOrValue.Value(4.0), lineColor = red))
        val dashed = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineDasharray = ExpressionOrValue.Value(listOf(1.0, 1.0)),
            )
        )

        assertTrue(
            dashed.opaquePixelCount() < solid.opaquePixelCount(),
            "a dashed line must cover less than the solid one"
        )
        assertTrue(dashed.opaquePixelCount() > 0, "but it must still draw something")
    }

    @Test
    fun `dasharray intervals scale with line width not with density`() {
        // Spec: "To convert a dash length to pixels, multiply the length by the current line width."
        assertEquals(listOf(6f, 12f), dashIntervals(listOf(1.0, 2.0), lineWidthPx = 6f)!!.toList())
    }

    @Test
    fun `an odd length dasharray is repeated so the pattern can alternate`() {
        val intervals = dashIntervals(listOf(1.0, 2.0, 3.0), lineWidthPx = 1f)!!.toList()

        assertEquals(listOf(1f, 2f, 3f, 1f, 2f, 3f), intervals)
    }

    @Test
    fun `a dasharray that cannot produce a dash is ignored`() {
        assertEquals(null, dashIntervals(listOf(4.0), lineWidthPx = 2f), "a single entry is not a pattern")
        assertEquals(null, dashIntervals(listOf(0.0, 0.0), lineWidthPx = 2f), "zero-length dashes")
        assertEquals(null, dashIntervals(listOf(1.0, 1.0), lineWidthPx = 0f), "a zero-width line")
    }

    @Test
    fun `line-blur widens the stroke with a faded halo`() = runTest {
        val sharp = render(LinePaint(lineWidth = ExpressionOrValue.Value(4.0), lineColor = red))
        val blurred = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineBlur = ExpressionOrValue.Value(6.0),
            )
        )

        assertColorEquals(Color.Red, blurred.pixelAt(32, 32), message = "the core stays fully opaque")
        assertEquals(0f, sharp.pixelAt(32, 38).alpha, "no halo without blur")
        assertTrue(blurred.pixelAt(32, 38).alpha > 0f, "the halo reaches beyond the core")
        assertTrue(blurred.pixelAt(32, 38).alpha < 1f, "and it is translucent")
    }

    @Test
    fun `polygon features are stroked ring by ring`() = runTest {
        val feature = Mvt.polygonFeature(Mvt.clockwiseRing(1024, 1024, 3072, 3072))

        val bitmap = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(4.0), lineColor = red),
            feature = feature,
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 16), message = "the top edge of the ring")
        assertEquals(0f, bitmap.pixelAt(32, 32).alpha, "the interior is not filled")
    }

    @Test
    fun `line-gradient colours the line along its length`() = runTest {
        // Parsed from JSON rather than built by hand, so the gradient goes through the same
        // serializer and expression compiler a real style would.
        val paint = json.decodeFromString(
            LinePaint.serializer(),
            """{
                "line-width": 8,
                "line-gradient": ["interpolate", ["linear"], ["line-progress"], 0, "#ff0000", 1, "#0000ff"]
            }"""
        )

        val bitmap = render(paint)

        val start = bitmap.pixelAt(4, 32)
        val end = bitmap.pixelAt(60, 32)
        assertTrue(start.red > start.blue, "the start of the line is red: $start")
        assertTrue(end.blue > end.red, "the end of the line is blue: $end")
    }

    @Test
    fun `point features are ignored`() = runTest {
        val bitmap = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(8.0), lineColor = red),
            feature = Mvt.pointFeature(2048 to 2048),
        )

        assertEquals(0, bitmap.opaquePixelCount())
    }

    private fun horizontalLine() = Mvt.lineFeature(listOf(0 to 2048, 4096 to 2048))

    private companion object {
        const val SIZE = 64
        val red = ExpressionOrValue.Value(Color.Red)
    }
}
