package ovh.plrapps.mapcompose.vector.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import ovh.plrapps.mapcompose.vector.renderer.assertColorEquals
import ovh.plrapps.mapcompose.vector.renderer.pixelAt
import ovh.plrapps.mapcompose.vector.renderer.renderToBitmap
import ovh.plrapps.mapcompose.vector.spec.sprites.Sprite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sheet lookup, namespacing and SDF recolouring.
 *
 * Lives in `skiaTest` for the usual reason: every case allocates an `ImageBitmap`, which Android
 * unit tests cannot do. The maths the SDF cases assert on is covered independently and without a
 * graphics backend by `SdfShadingTest`.
 */
class SpriteManagerTest {

    private fun solidSheet(color: Color, size: Int = 8): ImageBitmap =
        renderToBitmap(size = size) { drawRect(color = color) }

    /** A sheet whose alpha channel ramps left to right, i.e. a one-dimensional distance field. */
    private fun distanceRamp(size: Int = 16): ImageBitmap =
        renderToBitmap(size = size) {
            for (x in 0 until size) {
                drawRect(
                    color = Color.White.copy(alpha = x.toFloat() / (size - 1)),
                    topLeft = Offset(x.toFloat(), 0f),
                    size = androidx.compose.ui.geometry.Size(1f, size.toFloat()),
                )
            }
        }

    private fun entry(width: Int, height: Int, x: Int = 0, y: Int = 0, sdf: Boolean = false) =
        Sprite(width = width, height = height, x = x, y = y, sdf = sdf)

    @Test
    fun `an unknown id resolves to nothing`() {
        val manager = SpriteManager(mapOf("dot" to entry(8, 8)), solidSheet(Color.Red))
        assertNull(manager.getSprite("missing"))
        assertNull(manager.getSpriteInfo("missing"))
    }

    @Test
    fun `a sprite is cut out of its own sheet`() {
        val manager = SpriteManager(
            listOf(
                SpriteSheet(mapOf("red" to entry(8, 8)), solidSheet(Color.Red)),
                SpriteSheet(mapOf("blue" to entry(8, 8)), solidSheet(Color.Blue)),
            )
        )
        val red = assertNotNull(manager.getSprite("red")).second
        val blue = assertNotNull(manager.getSprite("blue")).second
        assertColorEquals(Color.Red, red.pixelAt(4, 4))
        assertColorEquals(Color.Blue, blue.pixelAt(4, 4))
    }

    @Test
    fun `every sheet contributes to the available images`() {
        val manager = SpriteManager(
            listOf(
                SpriteSheet(mapOf("a" to entry(8, 8)), solidSheet(Color.Red)),
                SpriteSheet(mapOf("b" to entry(8, 8)), solidSheet(Color.Blue)),
            )
        )
        assertEquals(setOf("a", "b"), manager.availableImages.toSet())
    }

    @Test
    fun `an id defined twice keeps the first sheet's entry`() {
        // The style lists its sheets in priority order.
        val manager = SpriteManager(
            listOf(
                SpriteSheet(mapOf("dup" to entry(8, 8)), solidSheet(Color.Red)),
                SpriteSheet(mapOf("dup" to entry(8, 8)), solidSheet(Color.Blue)),
            )
        )
        assertColorEquals(Color.Red, assertNotNull(manager.getSprite("dup")).second.pixelAt(4, 4))
    }

    @Test
    fun `a plain sprite is never recoloured`() {
        // `icon-color` is SDF-only: upstream's ordinary-icon program has no colour uniform at all
        // (`symbol_icon.fragment.glsl`). A plain entry used to be tinted by it through a `SrcIn`
        // fill, which flattened every multicolour PNG icon to one colour.
        val manager = SpriteManager(mapOf("dot" to entry(8, 8)), solidSheet(Color.White))
        val plain = assertNotNull(manager.getSprite("dot", sdf = SDF(fillColor = Color.Green))).second
        assertColorEquals(Color.White, plain.pixelAt(4, 4))
    }

    @Test
    fun `an SDF sprite with no explicit shading does not throw`() {
        // It used to `error("sdf not provided")`, so an SDF icon in a layer that set no icon-color
        // crashed the painter rather than rendering the spec default.
        val manager = SpriteManager(mapOf("sdf" to entry(16, 16, sdf = true)), distanceRamp())
        val shaded = assertNotNull(manager.getSprite("sdf")).second
        val pixels = shaded.toPixelMap()
        val opaque = (0 until pixels.width).map { pixels[it, pixels.height / 2] }.filter { it.alpha > 0.5f }
        assertTrue(opaque.isNotEmpty(), "the spec default icon-color must still draw the shape")
        for (color in opaque) assertColorEquals(Color.Black, color.copy(alpha = 1f))
    }

    @Test
    fun `an SDF sprite is filled with icon-color`() {
        val manager = SpriteManager(mapOf("sdf" to entry(16, 16, sdf = true)), distanceRamp())
        val shaded = assertNotNull(manager.getSprite("sdf", sdf = SDF(fillColor = Color.Red))).second
        val pixels = shaded.toPixelMap()
        // The ramp's right-hand end is deep inside the shape.
        assertColorEquals(Color.Red, pixels[pixels.width - 1, pixels.height / 2])
    }

    @Test
    fun `a halo covers ground the fill alone does not`() {
        val manager = SpriteManager(mapOf("sdf" to entry(16, 16, sdf = true)), distanceRamp())

        fun coveredColumns(sdf: SDF): Int {
            val pixels = assertNotNull(manager.getSprite("sdf", sdf = sdf)).second.toPixelMap()
            val row = pixels.height / 2
            return (0 until pixels.width).count { pixels[it, row].alpha > 0.5f }
        }

        val noHalo = coveredColumns(SDF(fillColor = Color.Red))
        val halo = coveredColumns(SDF(fillColor = Color.Red, haloColor = Color.Blue, haloWidth = 2f))
        assertTrue(halo > noHalo, "a halo must reach outside the shape, got $halo vs $noHalo")
    }

    @Test
    fun `a wider halo reaches further`() {
        val manager = SpriteManager(mapOf("sdf" to entry(16, 16, sdf = true)), distanceRamp())

        fun coveredColumns(width: Float): Int {
            val sdf = SDF(fillColor = Color.Red, haloColor = Color.Blue, haloWidth = width)
            val pixels = assertNotNull(manager.getSprite("sdf", sdf = sdf)).second.toPixelMap()
            val row = pixels.height / 2
            return (0 until pixels.width).count { pixels[it, row].alpha > 0.5f }
        }

        assertTrue(coveredColumns(4f) > coveredColumns(1f))
    }

    @Test
    fun `a shaded sprite is cached per shading`() {
        val manager = SpriteManager(mapOf("sdf" to entry(16, 16, sdf = true)), distanceRamp())
        val red = assertNotNull(manager.getSprite("sdf", sdf = SDF(fillColor = Color.Red))).second
        val green = assertNotNull(manager.getSprite("sdf", sdf = SDF(fillColor = Color.Green))).second
        val redAgain = assertNotNull(manager.getSprite("sdf", sdf = SDF(fillColor = Color.Red))).second
        assertTrue(red === redAgain, "the same shading must hit the cache")
        assertTrue(red !== green, "a different shading must not")
    }
}
