package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The colour ramp `color-relief` draws through -- `ColorReliefRamp.kt`.
 *
 * Pure maths over a compiled property, so `commonTest` rather than `skiaTest`, the same split
 * `HillshadeShadingTest` and `HeatmapKernelTest` use. What is asserted is upstream's *pair*:
 * `ColorReliefStyleLayer._createColorRamp` picking the stops and `color_relief.fragment.glsl`
 * blending between two of them.
 */
class ColorReliefRampTest {

    private fun colorProperty(source: String): ExpressionOrValue<Color> =
        json.decodeFromString<ExpressionOrValue<Color>>(source)

    private fun rampOf(source: String) = colorReliefRamp(colorProperty(source))

    private fun assertChannels(expected: Color, actual: Color, tolerance: Float = 1e-3f) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance &&
                abs(expected.alpha - actual.alpha) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun `an interpolate over elevation becomes the ramp's stops`() {
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["elevation"],0,"#000000",1000,"#ffffff"]""")
        )

        assertEquals(listOf(0.0, 1000.0), ramp.elevationStops)
        assertEquals(listOf(Color.Black, Color.White), ramp.colorStops)
    }

    @Test
    fun `a stop's own elevation reads back as that stop's colour`() {
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["elevation"],0,"#ff0000",500,"#00ff00",1000,"#0000ff"]""")
        )

        assertEquals(Color.Red, ramp.colorAt(0.0))
        assertEquals(Color.Green, ramp.colorAt(500.0))
        assertEquals(Color.Blue, ramp.colorAt(1000.0))
    }

    @Test
    fun `between two stops the two colours are blended`() {
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["elevation"],0,"#000000",1000,"#ffffff"]""")
        )

        assertChannels(Color(0.25f, 0.25f, 0.25f), ramp.colorAt(250.0))
        assertChannels(Color(0.5f, 0.5f, 0.5f), ramp.colorAt(500.0))
    }

    @Test
    fun `outside the ramp the end stops are clamped to`() {
        // The shader's `x` leaves 0..1 there and CLAMP_TO_EDGE pins it to the end texel.
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["elevation"],0,"#ff0000",1000,"#0000ff"]""")
        )

        assertEquals(Color.Red, ramp.colorAt(-8000.0))
        assertEquals(Color.Blue, ramp.colorAt(9000.0))
    }

    @Test
    fun `the blend is premultiplied so a transparent stop does not wash out the colour`() {
        /* Opaque red to fully transparent blue. Premultiplied -- which is what the colour texture
         * holds -- the midpoint keeps red's hue at half alpha; blending the raw channels instead
         * would drag it halfway to blue. */
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["elevation"],0,"rgba(255,0,0,1)",100,"rgba(0,0,255,0)"]""")
        )

        val mid = ramp.colorAt(50.0)
        assertChannels(Color(red = 1f, green = 0f, blue = 0f, alpha = 0.5f), mid)
    }

    @Test
    fun `the blend is linear whatever the interpolation type says`() {
        /* Upstream reads only the stop *labels* off the interpolate and lets GL's LINEAR filter
         * blend the two texels, so an exponential ramp is flattened between its stops. Evaluating
         * the expression itself at 500 would give roughly 0.41 here, not 0.5. */
        val ramp = assertNotNull(
            rampOf("""["interpolate",["exponential",2],["elevation"],0,"#000000",1000,"#ffffff"]""")
        )

        assertChannels(Color(0.5f, 0.5f, 0.5f), ramp.colorAt(500.0))
    }

    @Test
    fun `a one-stop ramp is widened by a metre so the blend has a span`() {
        val ramp = assertNotNull(rampOf("""["interpolate",["linear"],["elevation"],120,"#ff0000"]"""))

        assertEquals(listOf(120.0, 121.0), ramp.elevationStops)
        assertEquals(listOf(Color.Red, Color.Red), ramp.colorStops)
        assertEquals(Color.Red, ramp.colorAt(120.5))
    }

    @Test
    fun `anything that is not an interpolate produces no ramp at all`() {
        // Upstream's empty ramp is one transparent stop, which draws nothing; here that is `null`,
        // so the painter returns before it allocates a bitmap.
        assertNull(rampOf("""["step",["elevation"],"#000000",1000,"#ffffff"]"""))
        assertNull(rampOf("""["case",[">",["elevation"],10],"#000000","#ffffff"]"""))
        assertNull(rampOf(""""#ff0000""""))
        assertNull(colorReliefRamp(null))
        assertNull(colorReliefRamp(ExpressionOrValue.Value(Color.Red)))
    }

    @Test
    fun `an interpolate on zoom rather than elevation still ramps`() {
        /* `_createColorRamp` does not check what the interpolate's input is -- it takes the labels
         * and evaluates. A style that interpolates on zoom therefore ramps by elevation instead,
         * which is upstream's behaviour and not this port's invention. */
        val ramp = assertNotNull(
            rampOf("""["interpolate",["linear"],["zoom"],0,"#000000",10,"#ffffff"]""")
        )

        assertEquals(listOf(0.0, 10.0), ramp.elevationStops)
    }
}
