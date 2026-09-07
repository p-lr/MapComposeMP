package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `circle` layer's radial profile, against `src/shaders/glsl/circle.fragment.glsl` evaluated by
 * hand.
 *
 * Everything here is a pure function of `t = distance / (radius + strokeWidth)`, so it needs no
 * graphics backend and runs on all four targets, unlike `CircleLayerPainterTest`.
 */
class CircleShadingTest {

    @Test
    fun `smoothstep runs backwards when its edges are reversed`() {
        // Upstream's `opacity_t` calls it that way: `smoothstep(0.0, antialiased_blur, ...)` with a
        // negative `antialiased_blur`. Reversed edges are legal GLSL, and the ramp descends.
        assertEquals(1f, smoothstep(0f, -1f, -1f))
        assertEquals(0.5f, smoothstep(0f, -1f, -0.5f), absoluteTolerance = 1e-6f)
        assertEquals(0f, smoothstep(0f, -1f, 0f))
        assertEquals(0f, smoothstep(0f, -1f, 1f), "clamped past the first edge")
    }

    @Test
    fun `the antialias band is one pixel wide unless blur widens it`() {
        // `-max(1.0 / u_device_pixel_ratio / (radius + stroke_width), blur)`, sign-flipped.
        assertEquals(0.05f, circleAntialiasBlur(totalRadius = 20f, blur = 0f), absoluteTolerance = 1e-6f)
        assertEquals(0.25f, circleAntialiasBlur(totalRadius = 20f, blur = 0.25f), absoluteTolerance = 1e-6f)
        assertEquals(
            0.05f, circleAntialiasBlur(totalRadius = 20f, blur = 0.01f), absoluteTolerance = 1e-6f,
            "a blur finer than a pixel is the pixel"
        )
    }

    @Test
    fun `coverage is full inside the band and gone at the edge`() {
        val aaBlur = 0.25f

        assertEquals(1f, circleCoverage(t = 0f, aaBlur = aaBlur))
        assertEquals(1f, circleCoverage(t = 1f - aaBlur, aaBlur = aaBlur))
        assertEquals(0f, circleCoverage(t = 1f, aaBlur = aaBlur))
        assertEquals(0f, circleCoverage(t = 1.5f, aaBlur = aaBlur))
    }

    @Test
    fun `the fade across the band is cubic and not linear`() {
        // Halfway through the band `smoothstep` is 0.5, which the old linear ramp also gave; a
        // quarter of the way in is where the two part company (0.156 against 0.25).
        val aaBlur = 0.4f
        assertEquals(0.5f, circleCoverage(t = 1f - aaBlur / 2f, aaBlur = aaBlur), absoluteTolerance = 1e-5f)
        assertEquals(0.15625f, circleCoverage(t = 1f - aaBlur / 4f, aaBlur = aaBlur), absoluteTolerance = 1e-5f)
    }

    @Test
    fun `circle-blur of 1 fades from the centre outwards`() {
        // radius 20, no stroke, blur 1: `B` is 1, so `opacity_t` is smoothstep(0, -1, d/20 - 1),
        // i.e. `s = 1 - d/20` shaped by `s*s*(3-2s)`. At d = 14 that is 0.216, where the old linear
        // fade over 1/(1+blur) of the radius gave 0.6.
        val aaBlur = circleAntialiasBlur(totalRadius = 20f, blur = 1f)
        assertEquals(1f, aaBlur)
        assertEquals(0.216f, circleCoverage(t = 14f / 20f, aaBlur = aaBlur), absoluteTolerance = 1e-3f)
        assertEquals(1f, circleCoverage(t = 0f, aaBlur = aaBlur), "the centre stays opaque")
    }

    @Test
    fun `blur feathers the stroke and not only the fill`() {
        // The band is a fraction of `radius + stroke_width`, so a stroked circle's *stroke* is what
        // fades. radius 10, stroke 6, blur 0.25: the band starts at t = 0.75, i.e. d = 12, which is
        // inside the stroke ring.
        val aaBlur = circleAntialiasBlur(totalRadius = 16f, blur = 0.25f)
        assertEquals(1f, circleCoverage(t = 10f / 16f, aaBlur = aaBlur), "the fill's edge is untouched")
        assertTrue(
            circleCoverage(t = 14f / 16f, aaBlur = aaBlur) < 1f,
            "the stroke fades before the disc's edge"
        )
        assertEquals(0f, circleCoverage(t = 1f, aaBlur = aaBlur))
    }

    @Test
    fun `the stroke colour ramps in over the band inside the fill radius`() {
        val aaBlur = circleAntialiasBlur(totalRadius = 16f, blur = 0f)
        val radiusRatio = 10f / 16f

        assertEquals(
            0f, circleStrokeMix(t = radiusRatio - aaBlur, radiusRatio, aaBlur, hasStroke = true),
            "the ramp starts a band inside the fill's edge"
        )
        assertEquals(1f, circleStrokeMix(t = radiusRatio, radiusRatio, aaBlur, hasStroke = true))
        assertEquals(1f, circleStrokeMix(t = 1f, radiusRatio, aaBlur, hasStroke = true))
        assertTrue(circleStrokeMix(t = radiusRatio - aaBlur / 2f, radiusRatio, aaBlur, hasStroke = true) > 0f)
    }

    @Test
    fun `a stroke thinner than upstream's threshold contributes no colour`() {
        // `stroke_width < 0.01 ? 0.0 : ...`
        val aaBlur = circleAntialiasBlur(totalRadius = 16f, blur = 0f)
        assertEquals(0f, circleStrokeMix(t = 1f, radiusRatio = 10f / 16f, aaBlur, hasStroke = false))
    }

    @Test
    fun `a circle-radius of zero is a disc of stroke colour`() {
        // `radius / (radius + stroke_width)` is 0, so `color_t` is 1 for every t >= 0.
        val aaBlur = circleAntialiasBlur(totalRadius = 6f, blur = 0f)
        for (t in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(1f, circleStrokeMix(t, radiusRatio = 0f, aaBlur, hasStroke = true), "at t=$t")
        }

        val stops = circleGradientStops(
            radius = 0f, strokeWidth = 6f, blur = 0f,
            fill = Color.Red, stroke = Color.Blue, hasStroke = true,
        )
        assertColorEquals(Color.Blue, stops.first().second)
    }

    @Test
    fun `the sampled stops walk the profile from the centre to the edge`() {
        val stops = circleGradientStops(
            radius = 10f, strokeWidth = 6f, blur = 0.25f,
            fill = Color.Red, stroke = Color.Blue, hasStroke = true,
        )

        assertEquals(0f, stops.first().first)
        assertEquals(1f, stops.last().first)
        for (i in 1 until stops.size) {
            assertTrue(stops[i].first > stops[i - 1].first, "stop $i is not past stop ${i - 1}")
        }
        assertColorEquals(Color.Red, stops.first().second)
        assertEquals(0f, stops.last().second.alpha, "the disc's edge is transparent")
    }

    @Test
    fun `the outermost stop keeps its hue so Skia does not interpolate towards black`() {
        // Skia interpolates gradient stops unpremultiplied, so a transparent *black* last stop would
        // drag every coloured circle's edge dark.
        val stops = circleGradientStops(
            radius = 10f, strokeWidth = 0f, blur = 0.5f,
            fill = Color.Red, stroke = Color.Blue, hasStroke = false,
        )
        val edge = stops.last().second
        assertEquals(0f, edge.alpha)
        assertEquals(1f, edge.red, absoluteTolerance = 1e-3f)
        assertEquals(0f, edge.green, absoluteTolerance = 1e-3f)
    }

    @Test
    fun `the fill and stroke colours are mixed premultiplied`() {
        // Upstream's `mix(color * opacity, stroke_color * stroke_opacity, color_t)` runs on
        // premultiplied colours, so a translucent stroke pulls the *hue* across only in proportion
        // to the colour it actually carries. Halfway through the ramp an opaque red fill and a
        // quarter-opaque blue stroke give alpha 0.625 and a red-dominated hue, where a straight-alpha
        // mix would give an even split.
        val stroke = Color.Blue.copy(alpha = 0.25f)
        val aaBlur = circleAntialiasBlur(totalRadius = 16f, blur = 0f)
        val radiusRatio = 10f / 16f
        val stops = circleGradientStops(
            radius = 10f, strokeWidth = 6f, blur = 0f,
            fill = Color.Red, stroke = stroke, hasStroke = true,
        )
        // The stop at the fill's own edge, where `color_t` is 1 and only the stroke is left.
        val atEdge = stops.first { it.first >= radiusRatio - 1e-4f }.second
        assertEquals(0.25f, atEdge.alpha, absoluteTolerance = 1e-3f)
        assertColorEquals(Color.Blue, atEdge.copy(alpha = 1f))

        val halfway = circleStrokeMix(radiusRatio - aaBlur / 2f, radiusRatio, aaBlur, hasStroke = true)
        assertEquals(0.5f, halfway, absoluteTolerance = 1e-5f)
        val mixedAlpha = 1f + (0.25f - 1f) * halfway
        assertEquals(0.625f, mixedAlpha, absoluteTolerance = 1e-5f)
    }

    private fun assertColorEquals(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, absoluteTolerance = 1e-3f, message = "red of $actual")
        assertEquals(expected.green, actual.green, absoluteTolerance = 1e-3f, message = "green of $actual")
        assertEquals(expected.blue, actual.blue, absoluteTolerance = 1e-3f, message = "blue of $actual")
    }
}
