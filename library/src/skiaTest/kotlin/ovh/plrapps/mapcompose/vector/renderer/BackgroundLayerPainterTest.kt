package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.SpriteManager
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.BackgroundLayer
import ovh.plrapps.mapcompose.vector.spec.style.background.BackgroundPaint
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pixel-level tests for [BackgroundLayerPainter], which covers the whole tile. */
class BackgroundLayerPainterTest {

    private suspend fun render(paint: BackgroundPaint, spriteManager: SpriteManager? = null) =
        renderToBitmap(size = SIZE) {
            BackgroundLayerPainter(spriteManager = spriteManager).paint(
                canvas = this,
                feature = EMPTY_FEATURE,
                style = BackgroundLayer(id = "background", paint = paint),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

    @Test
    fun `a background with no colour uses the spec default black`() = runTest {
        val bitmap = render(BackgroundPaint())

        assertColorEquals(Color.Black, bitmap.pixelAt(0, 0))
        assertColorEquals(Color.Black, bitmap.pixelAt(SIZE - 1, SIZE - 1), message = "the whole tile")
    }

    @Test
    fun `background-color is honoured`() = runTest {
        val bitmap = render(BackgroundPaint(backgroundColor = ExpressionOrValue.Value(Color.Red)))

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `background-opacity multiplies the colour alpha`() = runTest {
        val bitmap = render(
            BackgroundPaint(
                backgroundColor = ExpressionOrValue.Value(Color.Red),
                backgroundOpacity = ExpressionOrValue.Value(0.5),
            )
        )

        assertEquals(0.5f, bitmap.pixelAt(32, 32).alpha, absoluteTolerance = 0.02f)
    }

    @Test
    fun `background-pattern paints the sprite`() = runTest {
        val bitmap = render(
            BackgroundPaint(
                backgroundColor = ExpressionOrValue.Value(Color.Red),
                backgroundPattern = ExpressionOrValue.Value("dots"),
            ),
            spriteManager = spriteSheet("dots", Color.Green),
        )

        assertColorEquals(Color.Green, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `an unresolvable background-pattern falls back to background-color`() = runTest {
        val bitmap = render(
            BackgroundPaint(
                backgroundColor = ExpressionOrValue.Value(Color.Red),
                backgroundPattern = ExpressionOrValue.Value("no-such-sprite"),
            )
        )

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32))
    }

    @Test
    fun `a layer that declares no paint draws the spec defaults`() = runTest {
        // Regression: an omitted `paint` object used to suppress the layer; upstream always
        // populates one from the spec (`style_layer.ts`).
        val bitmap = renderToBitmap(size = SIZE) {
            BackgroundLayerPainter(spriteManager = null).paint(
                canvas = this,
                feature = EMPTY_FEATURE,
                style = BackgroundLayer(id = "background"),
                canvasSize = SIZE,
                extent = Mvt.DEFAULT_EXTENT,
                zoom = 10.0,
                featureProperties = null,
                actualZoom = 10.0,
            )
        }

        assertColorEquals(Color.Black, bitmap.pixelAt(32, 32), message = "default background-color")
    }

    private companion object {
        const val SIZE = 64

        val EMPTY_FEATURE = Tile.Feature(
            id = -1,
            type = Tile.GeomType.POINT,
            geometry = emptyList(),
            tags = emptyList(),
        )
    }
}
