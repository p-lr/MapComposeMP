package ovh.plrapps.mapcompose.vector.spec.style.expression.colorspaces

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.spec.style.expression.colorToRgbaString
import ovh.plrapps.mapcompose.vector.spec.style.expression.definitions.expressions
import ovh.plrapps.mapcompose.vector.spec.style.utils.ColorParser
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The applicable part of `maplibre-style-spec/src/expression/types/color.test.ts`.
 *
 * Upstream's `Color` class is not ported — MapCompose uses `androidx.compose.ui.graphics.Color` —
 * so the cases covering its constructor, its static members and its premultiplication bookkeeping
 * do not transfer. What does transfer is the observable behaviour: rgba serialization, and
 * interpolation in each of the three colour spaces.
 *
 * Two adaptations:
 * - **Comparison is premultiplied**, matching upstream's `expectToMatchColor`, which reads the
 *   premultiplied `r`/`g`/`b` fields. Compose stores unpremultiplied components, so they are
 *   multiplied by alpha here.
 * - **Tolerance is 1/255**, because Compose stores sRGB channels as 8 bits where upstream keeps
 *   doubles. The full-precision behaviour of the underlying conversions is asserted to 4 decimal
 *   places by `ColorSpacesUpstreamTest`.
 */
class ColorInterpolationUpstreamTest {

    private val eightBit = 1.0 / 255.0

    /** `expectToMatchColor(actual, "rgb(r% g% b% / a)")`, in premultiplied space. */
    private fun assertMatchesColor(actual: Color, expectedSerialized: String) {
        val match = Regex("""^rgb\(([\d.]+)% ([\d.]+)% ([\d.]+)% / ([\d.]+)\)$""")
            .matchEntire(expectedSerialized)
            ?: throw AssertionError("bad expectation: $expectedSerialized")
        val (r, g, b, a) = match.groupValues.drop(1).map { it.toDouble() }
        val multiplier = if (a != 0.0) a else 1.0

        fun check(name: String, expected: Double, got: Double) {
            assertTrue(
                abs(expected - got) <= eightBit,
                "$name: expected $expected, got $got (from $expectedSerialized)",
            )
        }

        check("r", r / 100 * multiplier, actual.red.toDouble() * actual.alpha)
        check("g", g / 100 * multiplier, actual.green.toDouble() * actual.alpha)
        check("b", b / 100 * multiplier, actual.blue.toDouble() * actual.alpha)
        check("a", a, actual.alpha.toDouble())
    }

    private operator fun <T> List<T>.component4(): T = this[3]

    private fun parse(css: String): Color = ColorParser.parseColorString(css)

    @Test
    fun `should serialize to rgba format`() {
        assertEquals("rgba(255,255,0,1)", colorToRgbaString(Color(1f, 1f, 0f, 1f)))
        assertEquals("rgba(51,0,255,0.3)", colorToRgbaString(Color(0.2f, 0f, 1f, 0.3f)))
        assertEquals("rgba(255,255,0,0)", colorToRgbaString(Color(1f, 1f, 0f, 0f)))
        assertEquals("rgba(128,0,128,1)", colorToRgbaString(parse("purple")))
        assertEquals("rgba(26,207,26,0.73)", colorToRgbaString(parse("rgba(26,207,26,.73)")))
        assertEquals("rgba(26,207,26,0)", colorToRgbaString(parse("rgba(26,207,26,0)")))
    }

    /**
     * Upstream tests a standalone `isSupportedInterpolationColorSpace` guard. Here the colour space
     * is chosen by the operator name, so the equivalent question is which names the registry knows.
     */
    @Test
    fun `should recognize supported interpolation color spaces`() {
        assertTrue(expressions.containsKey("interpolate"))
        assertTrue(expressions.containsKey("interpolate-hcl"))
        assertTrue(expressions.containsKey("interpolate-lab"))
    }

    @Test
    fun `should ignore invalid interpolation color spaces`() {
        for (name in listOf("sRGB", "HCL", "LCH", "LAB", "interpolate-HCL", "interpolate-LAB")) {
            assertFalse(expressions.containsKey(name), "'$name' should not be an operator")
        }
    }

    @Test
    fun `should interpolate colors in rgb color space`() {
        val color = parse("rgba(0,0,255,1)")
        val target = parse("rgba(0,255,0,.6)")
        fun at(t: Double) = interpolateColor(color, target, t, InterpolationColorSpace.RGB)

        assertMatchesColor(at(0.0), "rgb(0% 0% 100% / 1)")
        assertMatchesColor(at(0.25), "rgb(0% 25% 75% / 0.9)")
        assertMatchesColor(at(0.5), "rgb(0% 50% 50% / 0.8)")
        assertMatchesColor(at(0.75), "rgb(0% 75% 25% / 0.7)")
        assertMatchesColor(at(1.0), "rgb(0% 100% 0% / 0.6)")
    }

    @Test
    fun `should interpolate colors in hcl color space`() {
        val color = parse("rgba(0,0,255,1)")
        val target = parse("rgba(0,255,0,.6)")
        fun at(t: Double) = interpolateColor(color, target, t, InterpolationColorSpace.HCL)

        assertMatchesColor(at(0.0), "rgb(0% 0% 100% / 1)")
        assertMatchesColor(at(0.25), "rgb(0% 49.37% 100% / 0.9)")
        assertMatchesColor(at(0.5), "rgb(0% 70.44% 100% / 0.8)")
        assertMatchesColor(at(0.75), "rgb(0% 87.54% 63.18% / 0.7)")
        assertMatchesColor(at(1.0), "rgb(0% 100% 0% / 0.6)")
    }

    @Test
    fun `should interpolate colors in lab color space`() {
        val color = parse("rgba(0,0,255,1)")
        val target = parse("rgba(0,255,0,.6)")
        fun at(t: Double) = interpolateColor(color, target, t, InterpolationColorSpace.LAB)

        assertMatchesColor(at(0.0), "rgb(0% 0% 100% / 1)")
        assertMatchesColor(at(0.25), "rgb(39.64% 34.55% 83.36% / 0.9)")
        assertMatchesColor(at(0.5), "rgb(46.42% 56.82% 65.91% / 0.8)")
        assertMatchesColor(at(0.75), "rgb(41.45% 78.34% 45.62% / 0.7)")
        assertMatchesColor(at(1.0), "rgb(0% 100% 0% / 0.6)")
    }

    @Test
    fun `should correctly interpolate colors with alpha=0`() {
        val color = parse("rgba(0,0,255,0)")
        val target = parse("rgba(0,255,0,1)")
        fun at(t: Double) = interpolateColor(color, target, t, InterpolationColorSpace.RGB)

        assertMatchesColor(at(0.0), "rgb(0% 0% 0% / 0)")
        assertMatchesColor(at(0.25), "rgb(0% 25% 75% / 0.25)")
        assertMatchesColor(at(0.5), "rgb(0% 50% 50% / 0.5)")
        assertMatchesColor(at(0.75), "rgb(0% 75% 25% / 0.75)")
        assertMatchesColor(at(1.0), "rgb(0% 100% 0% / 1)")
    }

    @Test
    fun `should limit interpolation results to sRGB gamut`() {
        val color = parse("royalblue")
        val target = parse("cyan")

        for (space in InterpolationColorSpace.entries) {
            val between = interpolateColor(color, target, 0.5, space)
            for ((name, channel) in listOf(
                "r" to between.red, "g" to between.green, "b" to between.blue, "a" to between.alpha,
            )) {
                assertTrue(channel >= 0f, "$space $name below gamut: $channel")
                assertTrue(channel <= 1f, "$space $name above gamut: $channel")
            }
        }
    }
}
