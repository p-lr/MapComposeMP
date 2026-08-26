package ovh.plrapps.mapcompose.vector.renderer.utils

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the heatmap kernel maths against maplibre-gl-js's shaders directly, rather than against the
 * painter: `src/shaders/glsl/heatmap.vertex.glsl` and `heatmap.fragment.glsl` for the kernel,
 * `heatmap_texture.fragment.glsl` for the two texture reads.
 */
class HeatmapKernelTest {

    @Test
    fun `the gaussian coefficient is one over sqrt of two pi`() {
        assertEquals(1.0 / sqrt(2.0 * kotlin.math.PI), GAUSS_COEF, 1e-15)
    }

    @Test
    fun `the truncation floor is one sixteenth of an 8-bit step`() {
        assertEquals(1.0 / 255.0 / 16.0, KERNEL_ZERO, 0.0)
    }

    @Test
    fun `the kernel peaks at the point itself`() {
        assertEquals(GAUSS_COEF, kernelValue(weight = 1.0, intensity = 1.0, distanceInRadii = 0.0), 1e-12)
        assertEquals(
            2.0 * 3.0 * GAUSS_COEF,
            kernelValue(weight = 2.0, intensity = 3.0, distanceInRadii = 0.0),
            1e-12,
        )
    }

    @Test
    fun `the kernel follows the fragment shader`() {
        // float d = -0.5 * 3.0 * 3.0 * dot(v_extrude, v_extrude);
        // float val = weight * u_intensity * GAUSS_COEF * exp(d);
        for (r in listOf(0.25, 0.5, 1.0, 1.5, 2.0)) {
            val expected = 1.5 * 0.75 * GAUSS_COEF * exp(-0.5 * 3.0 * 3.0 * r * r)
            assertEquals(expected, kernelValue(weight = 1.5, intensity = 0.75, distanceInRadii = r), 1e-15)
        }
    }

    @Test
    fun `the kernel falls off monotonically`() {
        var previous = Double.MAX_VALUE
        for (step in 0..20) {
            val value = kernelValue(weight = 1.0, intensity = 1.0, distanceInRadii = step * 0.1)
            assertTrue(value < previous, "value at ${step * 0.1} radii must be below the previous one")
            previous = value
        }
    }

    @Test
    fun `the kernel extent is where the kernel reaches the truncation floor`() {
        for ((weight, intensity) in listOf(1.0 to 1.0, 4.0 to 1.0, 1.0 to 8.0, 0.5 to 0.5)) {
            val extent = kernelExtentInRadii(weight, intensity)
            assertEquals(
                KERNEL_ZERO,
                kernelValue(weight, intensity, extent),
                1e-12,
                "S must solve weight * intensity * GAUSS_COEF * exp(-0.5 * 3^2 * S^2) == ZERO",
            )
        }
    }

    @Test
    fun `a heavier point reaches further`() {
        val light = kernelExtentInRadii(weight = 1.0, intensity = 1.0)
        val heavy = kernelExtentInRadii(weight = 10.0, intensity = 1.0)
        assertTrue(heavy > light, "a weight of 10 must reach further than a weight of 1")

        // The default case: sqrt(-2 * ln(ZERO / GAUSS_COEF)) / 3
        assertEquals(sqrt(-2.0 * kotlin.math.ln(KERNEL_ZERO / GAUSS_COEF)) / 3.0, light, 1e-12)
    }

    @Test
    fun `a kernel that never clears the floor has no extent`() {
        assertEquals(0.0, kernelExtentInRadii(weight = 0.0, intensity = 1.0))
        assertEquals(0.0, kernelExtentInRadii(weight = 1.0, intensity = 0.0))
        // Upstream collapses the quad here too, via log() of a negative number.
        assertEquals(0.0, kernelExtentInRadii(weight = -2.0, intensity = 1.0))
        // A peak that is itself below ZERO is nowhere worth accumulating.
        assertEquals(0.0, kernelExtentInRadii(weight = KERNEL_ZERO / GAUSS_COEF / 2.0, intensity = 1.0))
    }

    @Test
    fun `bilinear sampling returns cell centres untouched`() {
        val field = floatArrayOf(
            0f, 1f,
            2f, 3f,
        )
        assertEquals(0.0, bilinearSample(field, dim = 2, x = 0.0, y = 0.0), 1e-9)
        assertEquals(1.0, bilinearSample(field, dim = 2, x = 1.0, y = 0.0), 1e-9)
        assertEquals(2.0, bilinearSample(field, dim = 2, x = 0.0, y = 1.0), 1e-9)
        assertEquals(3.0, bilinearSample(field, dim = 2, x = 1.0, y = 1.0), 1e-9)
    }

    @Test
    fun `bilinear sampling interpolates between cells`() {
        val field = floatArrayOf(
            0f, 1f,
            2f, 3f,
        )
        assertEquals(0.5, bilinearSample(field, dim = 2, x = 0.5, y = 0.0), 1e-9)
        assertEquals(1.0, bilinearSample(field, dim = 2, x = 0.0, y = 0.5), 1e-9)
        assertEquals(1.5, bilinearSample(field, dim = 2, x = 0.5, y = 0.5), 1e-9)
    }

    @Test
    fun `bilinear sampling clamps to the edge`() {
        val field = floatArrayOf(
            0f, 1f,
            2f, 3f,
        )
        assertEquals(0.0, bilinearSample(field, dim = 2, x = -5.0, y = -5.0), 1e-9)
        assertEquals(3.0, bilinearSample(field, dim = 2, x = 9.0, y = 9.0), 1e-9)
        assertEquals(0.0, bilinearSample(FloatArray(0), dim = 0, x = 0.0, y = 0.0), 1e-9)
    }

    @Test
    fun `the colour ramp is read at its ends and interpolated between`() {
        val ramp = IntArray(COLOR_RAMP_RESOLUTION) { i ->
            val v = i * 255 / (COLOR_RAMP_RESOLUTION - 1)
            (0xFF shl 24) or (v shl 16)
        }

        assertEquals(0xFF000000.toInt(), sampleColorRamp(ramp, 0.0))
        assertEquals(0xFFFF0000.toInt(), sampleColorRamp(ramp, 1.0))
        // Clamped rather than wrapped, matching CLAMP_TO_EDGE.
        assertEquals(0xFF000000.toInt(), sampleColorRamp(ramp, -1.0))
        assertEquals(0xFFFF0000.toInt(), sampleColorRamp(ramp, 4.0))

        val mid = sampleColorRamp(ramp, 0.5)
        assertTrue(abs(((mid shr 16) and 0xFF) - 128) <= 1, "midpoint red should be about 128")
    }

    @Test
    fun `the colour ramp interpolates every channel straight`() {
        val ramp = intArrayOf(0x00000000, 0xFFFFFFFF.toInt())

        val mid = sampleColorRamp(ramp, 0.5)
        for (shift in listOf(24, 16, 8, 0)) {
            assertTrue(
                abs(((mid ushr shift) and 0xFF) - 128) <= 1,
                "channel at bit $shift should be about 128, was ${(mid ushr shift) and 0xFF}",
            )
        }
        assertEquals(0, sampleColorRamp(IntArray(0), 0.5))
    }
}
