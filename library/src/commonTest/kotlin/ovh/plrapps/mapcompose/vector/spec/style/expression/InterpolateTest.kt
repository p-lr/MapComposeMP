package ovh.plrapps.mapcompose.vector.spec.style.expression

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The interpolation curves. The previous implementation parsed `cubic-bezier` and `step` and then
 * silently used linear for all of them, and hard-coded an exponential base of 1 (also linear).
 */
class InterpolateTest {

    private val bare = Json { ignoreUnknownKeys = true }

    private fun number(source: String, zoom: Double): Double? =
        bare.decodeFromString(ExpressionOrValueSerializer(Double.serializer()), source).processAsDouble(zoom = zoom)

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    @Test
    fun linearInterpolationIsProportional() {
        val src = """["interpolate",["linear"],["zoom"],0,0,10,100]"""
        assertClose(0.0, number(src, 0.0))
        assertClose(50.0, number(src, 5.0))
        assertClose(100.0, number(src, 10.0))
        // Outside the stop range the endpoints are held.
        assertClose(0.0, number(src, -5.0))
        assertClose(100.0, number(src, 20.0))
    }

    /**
     * With base 2 the midpoint is `(2^0.5 - 1) / (2^1 - 1)` ≈ 0.4142 of the way, not 0.5. The old
     * implementation ignored the base for the scalar `"exponential"` head and produced 0.5.
     */
    @Test
    fun exponentialInterpolationHonoursItsBase() {
        val src = """["interpolate",["exponential",2],["zoom"],0,0,1,100]"""
        assertClose(41.42135623730951, number(src, 0.5), tolerance = 1e-9)
    }

    @Test
    fun exponentialBaseOneIsLinear() {
        val src = """["interpolate",["exponential",1],["zoom"],0,0,10,100]"""
        assertClose(50.0, number(src, 5.0))
    }

    /** `cubic-bezier(0, 0, 1, 1)` is the identity, so it must agree with linear. */
    @Test
    fun cubicBezierIdentityMatchesLinear() {
        val src = """["interpolate",["cubic-bezier",0,0,1,1],["zoom"],0,0,10,100]"""
        assertClose(50.0, number(src, 5.0), tolerance = 1e-5)
    }

    /** `cubic-bezier(0.5, 0, 0.5, 1)` is symmetric: it eases in and out around the midpoint. */
    @Test
    fun cubicBezierEaseInOutIsSymmetric() {
        val src = """["interpolate",["cubic-bezier",0.5,0,0.5,1],["zoom"],0,0,10,100]"""
        assertClose(50.0, number(src, 5.0), tolerance = 1e-4)
        val quarter = number(src, 2.5)!!
        val threeQuarter = number(src, 7.5)!!
        assertTrue(quarter < 25.0, "ease-in should lag linear, got $quarter")
        assertTrue(threeQuarter > 75.0, "ease-out should lead linear, got $threeQuarter")
        assertClose(100.0, quarter + threeQuarter, tolerance = 1e-4)
    }

    @Test
    fun colorsInterpolateInSrgb() {
        val expr = json.decodeFromString<ExpressionOrValue<Color>>(
            """["interpolate",["linear"],["zoom"],0,"#000000",10,"#ffffff"]"""
        )
        val mid = expr.processAsColor(zoom = 5.0)!!
        // 8-bit storage, so the midpoint lands on 128/255.
        assertTrue(abs(mid.red - 0.5f) <= 1f / 255f, "red was ${mid.red}")
        assertEquals(mid.red, mid.green)
        assertEquals(mid.red, mid.blue)
    }

    /**
     * `interpolate-hcl` and `interpolate-lab` go through a perceptual color space, so the midpoint
     * between red and blue is far from the sRGB midpoint.
     *
     * The expected values were computed by running MapLibre's own `color_spaces.ts` formulas in
     * node against the same inputs, so this is a cross-check against the reference implementation
     * rather than a snapshot of our own output.
     */
    @Test
    fun hclAndLabMatchTheReferenceColorSpaceConversions() {
        fun mid(operator: String): Color = json.decodeFromString<ExpressionOrValue<Color>>(
            """["$operator",["linear"],["zoom"],0,"#ff0000",10,"#0000ff"]"""
        ).processAsColor(zoom = 5.0)!!

        val srgb = mid("interpolate")
        val hcl = mid("interpolate-hcl")
        val lab = mid("interpolate-lab")

        assertEquals(Color(0.5019608f, 0f, 0.5019608f, 1f), srgb)

        // Reference: labToRgb(lerp(rgbToLab(red), rgbToLab(blue), 0.5))
        assertChannels(expected = listOf(0.756820, 0.0, 0.534008), actual = lab)
        // Reference: hclToRgb over the shortest hue path from red to blue
        assertChannels(expected = listOf(0.960586, 0.0, 0.525883), actual = hcl)

        assertTrue(hcl != srgb, "interpolate-hcl should not match the sRGB midpoint")
        assertTrue(lab != srgb, "interpolate-lab should not match the sRGB midpoint")
    }

    /** Compares against reference channel values, allowing for Compose Color's 8-bit storage. */
    private fun assertChannels(expected: List<Double>, actual: Color) {
        val channels = listOf(actual.red, actual.green, actual.blue)
        for (i in expected.indices) {
            assertTrue(
                abs(expected[i] - channels[i]) <= 1.0 / 255.0,
                "channel $i: expected ${expected[i]}, got ${channels[i]}",
            )
        }
    }

    @Test
    fun aSingleStopEvaluatesToThatStop() {
        assertClose(7.0, number("""["interpolate",["linear"],["zoom"],5,7]""", 0.0))
        assertClose(7.0, number("""["interpolate",["linear"],["zoom"],5,7]""", 100.0))
    }
}
