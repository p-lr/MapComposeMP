package ovh.plrapps.mapcompose.vector.data.extension

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.io.Buffer
import ovh.plrapps.mapcompose.core.decodeFirstLayer
import ovh.plrapps.mapcompose.core.makeWorkerData
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.renderer.pixelAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A rasterized tile survives the trip across the `TileStreamProvider` boundary.
 *
 * This is the test that decides the encoding. `toBytes()` writes an uncompressed BMP, and nothing
 * in the vector package decodes it -- MapCompose's own `TileCollector` does, which is out of scope
 * for changes. So the assertion has to run against **core's real decoder**, not against a
 * re-implementation of it, and it has to run on every skia target: if it fails on wasm alone, that
 * means skiko's wasm build has no BMP codec and `toBytes()` has to move out of `skiaMain` into
 * `desktopMain` + `iosMain` with wasm left on PNG.
 *
 * The half-transparent pixel is the point of the fixture. A tile `ImageBitmap` is `PREMUL` and BMP
 * is straight alpha, so an encoder that hands the bitmap's own bytes over unconverted passes every
 * opaque assertion and quietly saturates every translucent one -- a label's halo, a hillshade
 * sample, the heatmap ramp's cold end.
 */
class BmpRoundTripTest {

    private val opaque = Color(1f, 0f, 0f, 1f)
    private val half = Color(0f, 0f, 1f, 0.5f)

    /** A 4x4 bitmap: an opaque quadrant, a half-transparent one, and two left clear. */
    private fun fixture(size: Int = 4): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(size.toFloat(), size.toFloat()),
        ) {
            drawRect(color = opaque, size = Size(size / 2f, size / 2f))
            drawRect(
                color = half,
                topLeft = androidx.compose.ui.geometry.Offset(size / 2f, 0f),
                size = Size(size / 2f, size / 2f),
            )
        }
        return bitmap
    }

    private fun ImageBitmap.throughCore(subSamplingRatio: Int = 1): ImageBitmap {
        val bytes = assertNotNull(toBytes(), "toBytes() returned null")
        val source = Buffer().apply { write(bytes) }

        return assertNotNull(
            source.decodeFirstLayer(
                hasLayers = false,
                optimizeForLowEndDevices = false,
                subSamplingRatio = subSamplingRatio,
                workerData = makeWorkerData(),
            ),
            "core could not decode the encoded tile -- this target has no BMP decoder"
        )
    }

    @Test
    fun `an encoded tile decodes back at the same size`() {
        val decoded = fixture(size = 8).throughCore()
        assertEquals(8, decoded.width)
        assertEquals(8, decoded.height)
    }

    @Test
    fun `an opaque pixel round-trips`() {
        val decoded = fixture().throughCore()
        assertColorEquals(opaque, decoded.pixelAt(0, 0))
    }

    @Test
    fun `a fully transparent pixel round-trips`() {
        val decoded = fixture().throughCore()
        assertEquals(0f, decoded.pixelAt(1, 3).alpha, "a clear pixel should stay clear")
    }

    @Test
    fun `a half-transparent pixel keeps its colour and is not saturated`() {
        val decoded = fixture().throughCore()
        // Tolerated loosely on alpha: a premultiplied round trip through 8-bit channels is lossy,
        // but a premultiply that was never undone would land the blue channel near 0.5, not 1.
        assertColorEquals(half, decoded.pixelAt(3, 0), tolerance = 0.02f)
    }

    @Test
    fun `rows are not flipped`() {
        // The fixture's top half is painted and its bottom half is not; a bottom-up BMP read as
        // top-down would swap the two.
        val decoded = fixture().throughCore()
        assertEquals(1f, decoded.pixelAt(0, 0).alpha, "top-left should be opaque")
        assertEquals(0f, decoded.pixelAt(0, 3).alpha, "bottom-left should be clear")
    }

    @Test
    fun `a sub-sampled decode still produces an image`() {
        // Core takes a different path for a sub-sampled tile -- `inSampleSize` on Android, `Codec`
        // or a raster `Surface` on skia -- and that path has to read BMP too.
        val decoded = fixture(size = 8).throughCore(subSamplingRatio = 2)
        assertEquals(4, decoded.width)
        assertEquals(4, decoded.height)
        assertColorEquals(opaque, decoded.pixelAt(0, 0))
    }
}
