package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
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
        // The point sits on the pixel corner (32, 32), so this pixel's centre is 13.51 px out.
        // `opacity_t` is smoothstep(0, -1, d/20 - 1), which is 0.248 there; the linear fade over
        // `1 / (1 + blur)` of the radius this painter used to draw gave 0.62.
        assertEquals(
            0.248f, blurred.pixelAt(32, 18).alpha, absoluteTolerance = 0.02f,
            message = "the shader's cubic fade, 13.51 px from the centre"
        )
    }

    @Test
    fun `circle-blur feathers the stroke and not only the fill`() = runTest {
        // The band is a fraction of `radius + stroke_width`, so it is the *stroke's* outer edge that
        // fades. radius 10 + stroke 6 with a blur of 0.5 starts the fade at d = 8, well inside the
        // ring, and at this pixel's 13.51 px `opacity_t` is 0.230.
        val paint = CirclePaint(
            circleRadius = ExpressionOrValue.Value(10.0),
            circleColor = red,
            circleStrokeWidth = ExpressionOrValue.Value(6.0),
            circleStrokeColor = ExpressionOrValue.Value(Color.Blue),
        )
        val sharp = render(paint)
        val blurred = render(paint.copy(circleBlur = ExpressionOrValue.Value(0.5)))

        assertEquals(1f, sharp.pixelAt(32, 18).alpha, absoluteTolerance = 0.02f, message = "no blur, no fade")
        assertEquals(
            0.230f, blurred.pixelAt(32, 18).alpha, absoluteTolerance = 0.02f,
            message = "the stroke fades with the disc"
        )
    }

    @Test
    fun `the fill and the stroke meet with no seam`() = runTest {
        // Regression: the fill used to be a disc drawn at `radius` and the stroke a ring over
        // `[radius, radius + strokeWidth]`, each antialiased by Skia on its own. Both then covered
        // the pixel at `radius` by half, which composites to 0.75 -- a light hairline around every
        // filled circle, where upstream's `color_t` smoothstep has none.
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(10.0),
                circleColor = red,
                circleStrokeWidth = ExpressionOrValue.Value(6.0),
                circleStrokeColor = ExpressionOrValue.Value(Color.Blue),
            )
        )

        // Everything inside the disc's own one-pixel antialias band is fully opaque.
        for (dy in -14..14) {
            assertEquals(
                1f, bitmap.pixelAt(32, 32 + dy).alpha, absoluteTolerance = 0.02f,
                message = "$dy px from the centre"
            )
        }
    }

    @Test
    fun `a circle-radius of zero still draws its stroke`() = runTest {
        // Upstream keeps the geometry -- the quad reaches `radius + stroke_width` whatever the
        // radius -- and `radius / (radius + stroke_width)` of 0 makes `color_t` 1 everywhere, so the
        // whole disc is stroke-coloured. This painter used to return on `radius <= 0`.
        val bitmap = render(
            CirclePaint(
                circleRadius = ExpressionOrValue.Value(0.0),
                circleColor = red,
                circleStrokeWidth = ExpressionOrValue.Value(8.0),
                circleStrokeColor = ExpressionOrValue.Value(Color.Blue),
            )
        )

        assertColorEquals(Color.Blue, bitmap.pixelAt(32, 32), message = "a disc of the stroke colour")
        assertColorEquals(Color.Blue, bitmap.pixelAt(32, 26), message = "6 px out, still inside")
        assertEquals(0f, bitmap.pixelAt(32, 22).alpha, "10 px out is past the stroke width")
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
    fun `a circle is drawn at every vertex of a line`() = runTest {
        // Upstream's `CircleBucket.addFeature` walks every vertex whatever the geometry type, so a
        // circle layer over a line source draws one circle per vertex. Only the middle vertex is
        // inside the tile here: the two ends sit exactly on its boundary.
        val line = render(
            CirclePaint(circleColor = red),
            Mvt.lineFeature(listOf(0 to 0, 2048 to 2048, 4096 to 4096)),
        )
        assertColorEquals(Color.Red, line.pixelAt(SIZE / 2, SIZE / 2))
        assertTrue(line.opaquePixelCount() > 0)
    }

    @Test
    fun `a circle is drawn at every vertex of a polygon ring`() = runTest {
        val polygon = render(
            CirclePaint(circleColor = red),
            Mvt.polygonFeature(Mvt.clockwiseRing(1024, 1024, 3072, 3072)),
        )
        // One circle per corner of the ring.
        for (corner in listOf(16 to 16, 48 to 16, 48 to 48, 16 to 48)) {
            assertColorEquals(Color.Red, polygon.pixelAt(corner.first, corner.second))
        }
    }

    @Test
    fun `a vertex whose disc misses the tile entirely is not drawn`() = runTest {
        // Two tile widths away, so nothing of it can reach in whatever the radius.
        val outside = render(CirclePaint(circleColor = red), Mvt.pointFeature(-8192 to 2048))
        assertEquals(0, outside.opaquePixelCount())
    }

    @Test
    fun `a vertex outside the tile still draws the part of its disc that reaches in`() = runTest {
        // The seam case. The MVT buffer carries this point into both tiles; upstream drops it here
        // because it draws every circle into one viewport-wide framebuffer, so the tile that owns
        // the point spills the whole disc across the boundary. A tile is rasterized on its own
        // here, so dropping it left the disc chopped in half with nobody drawing the rest.
        val onEdge = render(CirclePaint(circleColor = red), Mvt.pointFeature(4096 to 2048))
        assertTrue(onEdge.opaquePixelCount() > 0, "the half of the disc inside this tile")
        assertColorEquals(Color.Red, onEdge.pixelAt(SIZE - 1, SIZE / 2))
        // ...and only that half: the centre of the tile is nowhere near it.
        assertEquals(0f, onEdge.pixelAt(SIZE / 2, SIZE / 2).alpha)
    }

    @Test
    fun `a layer that declares no paint draws the spec defaults`() = runTest {
        // Regression: `style.paint ?: return` made an omitted `paint` object suppress the layer
        // entirely, where maplibre-gl-js always populates one from the spec (`style_layer.ts`), so
        // omitting it and writing `"paint": {}` are the same thing.
        val bitmap = renderToBitmap(size = SIZE) {
            painter.paint(
                canvas = this,
                feature = centrePoint(),
                style = CircleLayer(id = "circle", sourceLayer = "test"),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

        assertColorEquals(Color.Black, bitmap.pixelAt(32, 32), message = "default circle-color")
        assertEquals(0f, bitmap.pixelAt(32, 42).alpha, "beyond the default radius")
    }

    private fun centrePoint() = Mvt.pointFeature(2048 to 2048)

    private companion object {
        const val SIZE = 64
        val red = ExpressionOrValue.Value(Color.Red)
    }
}
