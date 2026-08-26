package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.test.runTest
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.HeatmapLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [HeatmapLayerPainter] by rendering into an off-screen bitmap and reading pixels back.
 *
 * The kernel maths itself is covered in `commonTest` by `HeatmapKernelTest`; what is asserted here
 * is what the painter does with it -- accumulation, the colour ramp, `heatmap-opacity`, and the
 * neighbouring tiles' points reaching in across the tile edge.
 *
 * Layers are parsed from style JSON rather than constructed, so `heatmap-color` goes through the
 * real serializer and expression compiler.
 */
class HeatmapLayerPainterTest {

    private fun heatmapLayer(paint: String = ""): HeatmapLayer {
        val body = if (paint.isEmpty()) "" else ""","paint":{$paint}"""
        return json.decodeFromString(
            Layer.serializer(),
            """{"id":"heat","type":"heatmap","source":"src","source-layer":"points"$body}""",
        ) as HeatmapLayer
    }

    /** A ramp that is transparent at density 0 and opaque red at 1, so alpha reads as density. */
    private val densityRamp =
        """"heatmap-color":["interpolate",["linear"],["heatmap-density"],""" +
            """0,"rgba(255, 0, 0, 0)",1,"rgba(255, 0, 0, 1)"]"""

    private fun point(x: Double, y: Double, properties: EvalFeature? = null) =
        HeatmapPoint(x = x, y = y, properties = properties)

    private suspend fun render(
        style: HeatmapLayer,
        points: List<HeatmapPoint>,
        zoom: Double = 10.0,
    ): ImageBitmap = renderToBitmap(size = SIZE) {
        HeatmapLayerPainter().paint(
            canvas = this,
            style = style,
            points = points,
            canvasSize = SIZE,
            actualZoom = zoom,
        )
    }

    @Test
    fun `a point heats the tile around it`() = runTest {
        val bitmap = render(heatmapLayer(densityRamp), listOf(point(32.0, 32.0)))

        assertTrue(bitmap.pixelAt(32, 32).alpha > 0.2f, "the point itself must be hot")
        assertEquals(0f, bitmap.pixelAt(0, 0).alpha, "a corner beyond the kernel must stay cold")
    }

    @Test
    fun `density falls off with distance from the point`() = runTest {
        val bitmap = render(heatmapLayer(densityRamp), listOf(point(32.0, 32.0)))

        var previous = Float.MAX_VALUE
        for (y in listOf(32, 38, 44, 50, 56)) {
            val alpha = bitmap.pixelAt(32, y).alpha
            assertTrue(alpha < previous, "alpha at y=$y must be below the previous sample")
            previous = alpha
        }
    }

    @Test
    fun `kernels add up`() = runTest {
        val one = render(heatmapLayer(densityRamp), listOf(point(32.0, 32.0)))
        val two = render(heatmapLayer(densityRamp), listOf(point(32.0, 32.0), point(32.0, 32.0)))

        assertTrue(
            two.pixelAt(32, 32).alpha > one.pixelAt(32, 32).alpha,
            "two coincident points must be hotter than one -- the density field is additive",
        )
    }

    @Test
    fun `heatmap-weight scales one point's contribution`() = runTest {
        val light = render(heatmapLayer("""$densityRamp,"heatmap-weight":0.2"""), listOf(point(32.0, 32.0)))
        val heavy = render(heatmapLayer("""$densityRamp,"heatmap-weight":0.8"""), listOf(point(32.0, 32.0)))

        assertTrue(heavy.pixelAt(32, 32).alpha > light.pixelAt(32, 32).alpha)
    }

    @Test
    fun `heatmap-weight is data-driven`() = runTest {
        val style = heatmapLayer("""$densityRamp,"heatmap-weight":["get","w"]""")
        val bitmap = render(
            style,
            listOf(
                point(16.0, 32.0, feature(mapOf("w" to 0.2))),
                point(48.0, 32.0, feature(mapOf("w" to 0.9))),
            ),
        )

        assertTrue(
            bitmap.pixelAt(48, 32).alpha > bitmap.pixelAt(16, 32).alpha,
            "the point whose feature carries the larger weight must be hotter",
        )
    }

    @Test
    fun `heatmap-intensity scales the whole field`() = runTest {
        val low = render(heatmapLayer("""$densityRamp,"heatmap-intensity":0.5"""), listOf(point(32.0, 32.0)))
        val high = render(heatmapLayer("""$densityRamp,"heatmap-intensity":2"""), listOf(point(32.0, 32.0)))

        assertTrue(high.pixelAt(32, 32).alpha > low.pixelAt(32, 32).alpha)
    }

    @Test
    fun `heatmap-radius widens the footprint`() = runTest {
        val narrow = render(heatmapLayer("""$densityRamp,"heatmap-radius":8"""), listOf(point(32.0, 32.0)))
        val wide = render(heatmapLayer("""$densityRamp,"heatmap-radius":24"""), listOf(point(32.0, 32.0)))

        assertTrue(
            wide.opaquePixelCount() > narrow.opaquePixelCount(),
            "a larger heatmap-radius must cover more of the tile",
        )
    }

    @Test
    fun `heatmap-opacity scales the drawn alpha`() = runTest {
        val full = render(heatmapLayer(densityRamp), listOf(point(32.0, 32.0)))
        val half = render(heatmapLayer("""$densityRamp,"heatmap-opacity":0.5"""), listOf(point(32.0, 32.0)))

        val expected = full.pixelAt(32, 32).alpha / 2f
        assertTrue(
            kotlin.math.abs(half.pixelAt(32, 32).alpha - expected) < 0.02f,
            "expected about $expected but was ${half.pixelAt(32, 32).alpha}",
        )
    }

    @Test
    fun `zero opacity draws nothing`() = runTest {
        val bitmap = render(heatmapLayer("""$densityRamp,"heatmap-opacity":0"""), listOf(point(32.0, 32.0)))

        assertEquals(0, bitmap.opaquePixelCount(), "upstream returns before it binds the framebuffer")
    }

    @Test
    fun `heatmap-color maps density to colour`() = runTest {
        val ramp = """"heatmap-color":["interpolate",["linear"],["heatmap-density"],""" +
            """0,"rgba(0, 0, 255, 0)",1,"rgba(0, 0, 255, 1)"]"""
        val bitmap = render(heatmapLayer(ramp), listOf(point(32.0, 32.0)))

        val hot = bitmap.pixelAt(32, 32)
        assertTrue(hot.alpha > 0.2f, "the point must be hot")
        assertTrue(hot.blue > 0.9f && hot.red < 0.1f, "the ramp is blue, but the pixel was $hot")
    }

    @Test
    fun `the spec default ramp applies when the style declares no colour`() = runTest {
        // Enough weight to saturate the density, which is the ramp's last stop: red.
        val bitmap = render(heatmapLayer(""""heatmap-weight":20"""), listOf(point(32.0, 32.0)))

        assertColorEquals(Color.Red, bitmap.pixelAt(32, 32), message = "the default ramp ends at red")
        /* A weight that saturates the centre also pushes the kernel's tail into the corners, so the
         * corner is not quite cold -- but the ramp's first stop is `rgba(0, 0, 255, 0)`, so a
         * density that low must still be all but transparent. */
        assertTrue(
            bitmap.pixelAt(0, 0).alpha < 0.05f,
            "the default ramp starts transparent, but the corner was ${bitmap.pixelAt(0, 0)}",
        )
    }

    @Test
    fun `a ramp that is opaque at zero density fills the whole tile`() = runTest {
        // Upstream's translucent pass draws a full-screen quad whether or not any tile had points.
        val ramp = """"heatmap-color":["interpolate",["linear"],["heatmap-density"],""" +
            """0,"rgba(0, 255, 0, 1)",1,"rgba(255, 0, 0, 1)"]"""
        val bitmap = render(heatmapLayer(ramp), emptyList())

        assertEquals(SIZE * SIZE, bitmap.opaquePixelCount())
        assertColorEquals(Color.Green, bitmap.pixelAt(0, 0))
    }

    @Test
    fun `a neighbouring tile's point heats this tile across the edge`() = runTest {
        // x is negative: the point belongs to the tile to the west, and its kernel reaches in.
        val bitmap = render(heatmapLayer(densityRamp), listOf(point(-6.0, 32.0)))

        assertTrue(bitmap.pixelAt(0, 32).alpha > 0.1f, "the west edge must be heated from outside")
        assertEquals(0f, bitmap.pixelAt(63, 32).alpha, "the east edge is out of the kernel's reach")
    }

    @Test
    fun `a point beyond the kernel's reach contributes nothing`() = runTest {
        val bitmap = render(heatmapLayer("""$densityRamp,"heatmap-radius":4"""), listOf(point(-40.0, 32.0)))

        assertEquals(0, bitmap.opaquePixelCount())
    }

    private fun feature(properties: Map<String, Any?>) =
        EvalFeature(type = "Point", id = null, properties = properties)

    private companion object {
        const val SIZE = 64
    }
}
