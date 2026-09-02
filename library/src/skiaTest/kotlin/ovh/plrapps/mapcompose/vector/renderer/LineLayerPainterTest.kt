package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.LineLayer
import ovh.plrapps.mapcompose.vector.spec.style.line.LineLayout
import ovh.plrapps.mapcompose.vector.spec.style.line.LinePaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.renderer.utils.dashPattern
import kotlin.math.abs
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
        val pattern = dashPattern(listOf(1.0, 2.0), lineWidthPx = 6f)!!
        assertEquals(listOf(6f, 12f), pattern.intervals.toList())
        assertEquals(0f, pattern.phase)
    }

    @Test
    fun `an odd length dasharray joins its last dash to its first`() {
        // Upstream's LineAtlas.getDashRanges starts its first range at -dasharray[last], so the
        // trailing dash and the leading one are one dash and the period is the plain sum.
        val pattern = dashPattern(listOf(1.0, 2.0, 3.0), lineWidthPx = 1f)!!
        assertEquals(listOf(4f, 2f), pattern.intervals.toList())
        assertEquals(3f, pattern.phase)
        assertEquals(6f, pattern.intervals.sum(), "the period is the sum of the original array")
    }

    @Test
    fun `a dasharray that cannot produce a dash is ignored`() {
        assertEquals(null, dashPattern(listOf(1.0), lineWidthPx = 1f), "a single entry")
        assertEquals(null, dashPattern(listOf(0.0, 0.0), lineWidthPx = 1f), "no dashes at all")
        assertEquals(null, dashPattern(listOf(1.0, 1.0), lineWidthPx = 0f), "a zero-width line")
    }

    @Test
    fun `line-blur fades the line from its centre without widening it`() = runTest {
        // Upstream's `outset` carries no blur term -- line.fragment.glsl softens the line within its
        // own width and never grows it. With line-width 4 and density 1 the ribbon reaches 2.5 px
        // either side whatever the blur is, and a blur of 6 gives `blur2 = 7`, so the alpha at a
        // pixel `d` from the centre is `(2.5 - d) / 7`.
        val sharp = render(LinePaint(lineWidth = ExpressionOrValue.Value(4.0), lineColor = red))
        val blurred = render(
            LinePaint(
                lineWidth = ExpressionOrValue.Value(4.0),
                lineColor = red,
                lineBlur = ExpressionOrValue.Value(6.0),
            )
        )

        // The row of pixels at y = 32 has its centres half a pixel below the line.
        assertEquals(1f, sharp.pixelAt(32, 32).alpha, "a sharp line is opaque at its centre")
        assertTrue(
            abs(blurred.pixelAt(32, 32).alpha - 2f / 7f) < 0.01f,
            "a blurred line is faint even at its centre: ${blurred.pixelAt(32, 32).alpha}"
        )
        assertTrue(
            abs(blurred.pixelAt(32, 33).alpha - 1f / 7f) < 0.01f,
            "and fades further out: ${blurred.pixelAt(32, 33).alpha}"
        )
        assertEquals(0f, blurred.pixelAt(32, 34).alpha, "the ribbon still ends where a sharp one does")
        assertEquals(0f, sharp.pixelAt(32, 34).alpha)
        assertTrue(
            blurred.opaquePixelCount() <= sharp.opaquePixelCount(),
            "blurring must not widen the line"
        )
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

    @Test
    fun `line-cap butt stops at the end point`() = runTest {
        val bitmap = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(8.0), lineColor = red),
            feature = Mvt.lineFeature(listOf(1024 to 2048, 3072 to 2048)),
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(20, 32), message = "inside the line")
        assertEquals(0f, bitmap.pixelAt(14, 32).alpha, "nothing past the first point at x = 16")
        assertEquals(0f, bitmap.pixelAt(49, 32).alpha, "nor past the last at x = 48")
    }

    @Test
    fun `line-cap square extends the line by its half width`() = runTest {
        val bitmap = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(8.0), lineColor = red),
            layout = LineLayout(lineCap = ExpressionOrValue.Value("square")),
            feature = Mvt.lineFeature(listOf(1024 to 2048, 3072 to 2048)),
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(14, 32), message = "the cap reaches back past x = 16")
        assertColorEquals(Color.Red, bitmap.pixelAt(49, 32), message = "and past x = 48")
        assertEquals(0f, bitmap.pixelAt(10, 32).alpha, "but only by half the width")
    }

    @Test
    fun `line-cap round rounds the end off`() = runTest {
        val square = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(8.0), lineColor = red),
            layout = LineLayout(lineCap = ExpressionOrValue.Value("square")),
            feature = Mvt.lineFeature(listOf(1024 to 2048, 3072 to 2048)),
        )
        val round = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(8.0), lineColor = red),
            layout = LineLayout(lineCap = ExpressionOrValue.Value("round")),
            feature = Mvt.lineFeature(listOf(1024 to 2048, 3072 to 2048)),
        )

        assertColorEquals(Color.Red, round.pixelAt(14, 32), message = "the round cap still reaches out")
        // The square cap's corner is 4.95 px from the end point, past the round cap's 4.5 radius.
        assertEquals(0f, round.pixelAt(12, 28).alpha, "but its corner is cut away")
        assertColorEquals(Color.Red, square.pixelAt(12, 28), message = "where the square cap keeps it")
    }

    @Test
    fun `the ribbon edge is feathered by one pixel`() = runTest {
        // A five pixel line at density 1 reaches 3 px either side of its centre, and the last pixel
        // of that is the shader's own antialias band -- upstream's `blur2 = 1 / dpr` with no blur.
        val bitmap = render(LinePaint(lineWidth = ExpressionOrValue.Value(5.0), lineColor = red))

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 33), message = "well inside the line")
        val edge = bitmap.pixelAt(32, 34).alpha
        assertTrue(edge > 0.1f && edge < 0.9f, "the edge pixel is partly covered: $edge")
        assertEquals(0f, bitmap.pixelAt(32, 35).alpha, "and the one past it is empty")
    }

    @Test
    fun `line-round-limit turns a round join into a miter`() = runTest {
        val corner = Mvt.lineFeature(listOf(512 to 512, 3584 to 512, 3584 to 3584))

        val rounded = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(12.0), lineColor = red),
            layout = LineLayout(lineJoin = ExpressionOrValue.Value("round")),
            feature = corner,
        )
        val mitered = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(12.0), lineColor = red),
            layout = LineLayout(
                lineJoin = ExpressionOrValue.Value("round"),
                // Above the corner's miter length of 1.41, so the round join is not worth drawing.
                lineRoundLimit = ExpressionOrValue.Value(2.0),
            ),
            feature = corner,
        )

        // The corner sits at (56, 8) and the ribbon reaches 6.5 px; the miter tip goes out to
        // (62.5, 1.5), past the arc a round join would cut.
        assertColorEquals(Color.Red, mitered.pixelAt(61, 3), message = "the miter reaches the corner")
        assertTrue(
            rounded.pixelAt(61, 3).alpha < mitered.pixelAt(61, 3).alpha,
            "a round join leaves the corner emptier than a miter"
        )
    }

    @Test
    fun `line-join bevel cuts the corner off`() = runTest {
        val corner = Mvt.lineFeature(listOf(512 to 512, 3584 to 512, 3584 to 3584))

        val mitered = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(12.0), lineColor = red),
            feature = corner,
        )
        val beveled = render(
            LinePaint(lineWidth = ExpressionOrValue.Value(12.0), lineColor = red),
            layout = LineLayout(lineJoin = ExpressionOrValue.Value("bevel")),
            feature = corner,
        )

        assertTrue(
            beveled.opaquePixelCount() < mitered.opaquePixelCount(),
            "a bevel covers less than a miter at the same corner"
        )
    }

    @Test
    fun `line-pattern paints the sprite along the line`() = runTest {
        val painterWithSprites = LineLayerPainter(spriteManager = spriteSheet("dot", Color.Green))
        val bitmap = renderToBitmap(size = SIZE) {
            painterWithSprites.paint(
                canvas = this,
                feature = horizontalLine(),
                style = LineLayer(
                    id = "line",
                    sourceLayer = "test",
                    paint = LinePaint(
                        lineWidth = ExpressionOrValue.Value(8.0),
                        lineColor = red,
                        linePattern = ExpressionOrValue.Value("dot"),
                    ),
                ),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

        assertColorEquals(Color.Green, bitmap.pixelAt(32, 32), message = "the pattern replaces the colour")
    }

    @Test
    fun `line-gradient interpolates between its stops`() = runTest {
        val paint = json.decodeFromString(
            LinePaint.serializer(),
            """{
                "line-width": 8,
                "line-gradient": ["interpolate", ["linear"], ["line-progress"], 0, "#ff0000", 1, "#0000ff"]
            }"""
        )

        val bitmap = render(paint)

        // Halfway along, the two stops meet -- which a per-segment gradient could not produce.
        val middle = bitmap.pixelAt(32, 32)
        assertTrue(abs(middle.red - 0.5f) < 0.1f, "half red at the midpoint: $middle")
        assertTrue(abs(middle.blue - 0.5f) < 0.1f, "and half blue: $middle")
    }

    @Test
    fun `a dashed line keeps its gradient progress along the whole line`() = runTest {
        val paint = json.decodeFromString(
            LinePaint.serializer(),
            """{
                "line-width": 8,
                "line-dasharray": [1, 1],
                "line-gradient": ["interpolate", ["linear"], ["line-progress"], 0, "#ff0000", 1, "#0000ff"]
            }"""
        )

        val bitmap = render(paint)

        // Dashes are eight pixels on, eight off, so x = 2 and x = 50 both land on a painted run.
        val start = (28..36).map { bitmap.pixelAt(2, it) }.maxBy { it.alpha }
        val end = (28..36).map { bitmap.pixelAt(50, it) }.maxBy { it.alpha }
        assertTrue(start.red > start.blue, "the first dash is still red: $start")
        assertTrue(end.blue > end.red, "and the last is still blue: $end")
    }

    @Test
    fun `line-width is in style pixels scaled by the draw scope density`() {
        // The contract the super-sampled tile density depends on: a painter states widths in
        // `canvas.density` units, so rasterizing a tile larger has to raise that density too or
        // super-sampling would thin every road instead of just smoothing it.
        val bitmap = renderToBitmap(size = SIZE, density = 2f) {
            runSynchronously {
                painter.paint(
                    canvas = this,
                    feature = horizontalLine(),
                    style = LineLayer(
                        id = "line",
                        sourceLayer = "test",
                        paint = LinePaint(lineWidth = ExpressionOrValue.Value(4.0), lineColor = red),
                    ),
                    canvasSize = SIZE,
                    extent = Mvt.DEFAULT_EXTENT,
                    zoom = 10.0,
                    featureProperties = null,
                    actualZoom = 10.0,
                )
            }
        }

        // Eight device pixels wide, so the ribbon reaches 4 px either side of y = 32.
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 28), message = "four pixels above the centre")
        assertColorEquals(Color.Red, bitmap.pixelAt(32, 35), message = "and below it")
        assertEquals(0f, bitmap.pixelAt(32, 27).alpha, "but not five above")
        assertEquals(0f, bitmap.pixelAt(32, 36).alpha, "nor five below")
    }

    private fun horizontalLine() = Mvt.lineFeature(listOf(0 to 2048, 4096 to 2048))

    private companion object {
        const val SIZE = 64
        val red = ExpressionOrValue.Value(Color.Red)
    }
}