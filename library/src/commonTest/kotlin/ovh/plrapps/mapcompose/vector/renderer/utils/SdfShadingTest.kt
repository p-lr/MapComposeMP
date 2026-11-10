package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SDF recolouring maths, against `maplibre-gl-js/src/shaders/symbol_sdf.fragment.glsl`.
 *
 * The shader's two thresholds are the whole behaviour: where the shape's own edge sits, and how far
 * outside it a halo of a given width reaches. Both were wrong before -- the halo was a fixed-width
 * ring drawn *inside* the edge -- so they are pinned here rather than left to the pixel tests.
 */
class SdfShadingTest {

    private val opaqueRed = Color.Red
    private val opaqueBlue = Color.Blue

    @Test
    fun `the fill edge is upstream's buffer`() {
        // (256 - 64) / 256: the atlas encodes 64 of its 256 levels as outside the shape.
        assertEquals(0.75f, SDF_FILL_BUFFER)
    }

    @Test
    fun `a zero halo lands on the fill edge`() {
        assertEquals(SDF_FILL_BUFFER, sdfHaloBuffer(haloWidth = 0f, fontScale = 1f))
    }

    @Test
    fun `a wider halo reaches further out`() {
        // A lower distance is further outside the shape, so a wider halo means a lower threshold.
        val narrow = sdfHaloBuffer(haloWidth = 1f, fontScale = 1f)
        val wide = sdfHaloBuffer(haloWidth = 3f, fontScale = 1f)
        assertTrue(wide < narrow, "expected $wide < $narrow")
        assertTrue(narrow < SDF_FILL_BUFFER)
    }

    @Test
    fun `the halo width is measured against the drawn scale`() {
        // Upstream's (6 - width / fontScale) / SDF_PX: a doubled icon needs half the threshold
        // shift for the same halo in layout pixels.
        assertEquals(
            sdfHaloBuffer(haloWidth = 2f, fontScale = 1f),
            sdfHaloBuffer(haloWidth = 4f, fontScale = 2f),
        )
    }

    @Test
    fun `deep inside the shape is the fill colour`() {
        val shaded = sdfPixel(
            distance = 1f, fillColor = opaqueRed, haloColor = opaqueBlue,
            haloWidth = 2f, haloBlur = 0f, fontScale = 1f,
        )
        assertEquals(opaqueRed, shaded)
    }

    @Test
    fun `far outside the halo is transparent`() {
        val shaded = sdfPixel(
            distance = 0f, fillColor = opaqueRed, haloColor = opaqueBlue,
            haloWidth = 2f, haloBlur = 0f, fontScale = 1f,
        )
        assertEquals(0f, shaded.alpha)
    }

    @Test
    fun `between the two edges is the halo colour`() {
        val haloBuffer = sdfHaloBuffer(haloWidth = 2f, fontScale = 1f)
        val between = (haloBuffer + SDF_FILL_BUFFER) / 2f
        val shaded = sdfPixel(
            distance = between, fillColor = opaqueRed, haloColor = opaqueBlue,
            haloWidth = 2f, haloBlur = 0f, fontScale = 1f,
        )
        assertEquals(opaqueBlue.red, shaded.red)
        assertEquals(opaqueBlue.blue, shaded.blue)
        assertTrue(shaded.alpha > 0.9f, "expected an opaque halo but alpha was ${shaded.alpha}")
    }

    @Test
    fun `the halo is behind the fill rather than added to it`() {
        // Summing the two passes brightened the boundary into a rim; compositing keeps the fill's
        // own colour wherever the fill is opaque.
        val shaded = sdfPixel(
            distance = 0.9f, fillColor = opaqueRed, haloColor = Color.White,
            haloWidth = 4f, haloBlur = 0f, fontScale = 1f,
        )
        assertEquals(opaqueRed, shaded)
    }

    @Test
    fun `a transparent halo leaves only the fill`() {
        val shaded = sdfPixel(
            distance = 0.6f, fillColor = opaqueRed, haloColor = Color.Transparent,
            haloWidth = 4f, haloBlur = 0f, fontScale = 1f,
        )
        assertEquals(0f, shaded.alpha)
    }

    @Test
    fun `halo blur widens the ramp`() {
        assertTrue(
            sdfGamma(haloBlur = 2f, fontScale = 1f, isHalo = true) >
                sdfGamma(haloBlur = 0f, fontScale = 1f, isHalo = true)
        )
        // The fill pass is never blurred, matching the shader's `u_is_halo` guard.
        assertEquals(
            sdfGamma(haloBlur = 0f, fontScale = 1f, isHalo = false),
            sdfGamma(haloBlur = 2f, fontScale = 1f, isHalo = false),
        )
    }

    @Test
    fun `smoothstep matches GLSL`() {
        assertEquals(0f, smoothstep(0f, 1f, -1f))
        assertEquals(1f, smoothstep(0f, 1f, 2f))
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f))
        // A degenerate edge pair is a hard step rather than a division by zero.
        assertEquals(1f, smoothstep(0.5f, 0.5f, 0.6f))
        assertEquals(0f, smoothstep(0.5f, 0.5f, 0.4f))
    }
}
