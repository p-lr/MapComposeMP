package ovh.plrapps.mapcompose.vector.spec.style

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.decodeStyle
import ovh.plrapps.mapcompose.vector.data.globalStateDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsFloat
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleGlobalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A style's root `state` block reaching the expressions it declares defaults for.
 *
 * `["global-state", k]` reads `GlobalProperties.globalState`, and the map it reads is baked into
 * each `StyleExpression` at compile time -- which happens inside the style's `Json` decode, where a
 * serializer has no context. `decodeStyle` is what closes that gap; see `StyleGlobalState`.
 */
class GlobalStateStyleTest {

    @Test
    fun `a paint property reads a declared default`() {
        val layer = fillLayer(
            state = """{"tint":{"default":"#ff0000"},"width":{"default":3}}""",
            paint = """"fill-color":["global-state","tint"]""",
        )

        assertEquals(Color.Red, layer.paint.fillColor.processAsColor())
    }

    @Test
    fun `a layer filter reads a declared default`() {
        val layer = fillLayer(
            state = """{"width":{"default":3}}""",
            paint = """"fill-color":"#ff0000"""",
            extra = ""","filter":["==",["get","w"],["global-state","width"]]""",
        )

        val filter = layer.filter?.filter
        assertTrue(filter != null)
        assertTrue(
            filter.filter(
                globals = GlobalProperties(zoom = 10.0),
                feature = EvalFeature(type = "Polygon", properties = mapOf("w" to 3.0)),
            )
        )
        assertTrue(
            !filter.filter(
                globals = GlobalProperties(zoom = 10.0),
                feature = EvalFeature(type = "Polygon", properties = mapOf("w" to 4.0)),
            )
        )
    }

    @Test
    fun `an undeclared key evaluates to the property default`() {
        val layer = fillLayer(
            state = """{"tint":{"default":"#ff0000"}}""",
            paint = """"fill-opacity":["global-state","nope"]""",
        )

        // Null, so the painter's `?: spec default` applies -- and no warning, as upstream.
        assertNull(layer.paint.fillOpacity.processAsFloat())
    }

    @Test
    fun `a declared key with no default is null rather than absent`() {
        val style = decodeStyle(
            """{"version":8,"state":{"x":{}},"sources":{},"layers":[]}"""
        )

        assertEquals(mapOf("x" to null), style.globalStateDefaults())
    }

    @Test
    fun `a style declaring no state compiles as before`() {
        val layer = fillLayer(state = null, paint = """"fill-color":["global-state","tint"]""")

        assertNull(layer.paint.fillColor.processAsColor())
    }

    @Test
    fun `the state block round-trips`() {
        val source = """{"version":8,"state":{"tint":{"default":"#ff0000"}},"sources":{},"layers":[]}"""
        val style = decodeStyle(source)

        assertEquals(mapOf("tint" to "#ff0000"), style.globalStateDefaults())
        assertEquals(1, style.state?.size)
    }

    @Test
    fun `the defaults are out of scope once the decode is over`() {
        decodeStyle("""{"version":8,"state":{"tint":{"default":"#ff0000"}},"sources":{},"layers":[]}""")

        // Otherwise the next style decoded would compile against this one's state.
        assertNull(StyleGlobalState.defaults)
    }

    @Test
    fun `visibility reads a declared default`() {
        val hidden = fillLayer(
            state = """{"hide":{"default":true}}""",
            paint = """"fill-color":"#ff0000"""",
            layout = """["case",["global-state","hide"],"none","visible"]""",
        )
        val shown = fillLayer(
            state = """{"hide":{"default":false}}""",
            paint = """"fill-color":"#ff0000"""",
            layout = """["case",["global-state","hide"],"none","visible"]""",
        )

        assertEquals("none", hidden.layout.visibility.processAsString())
        assertEquals("visible", shown.layout.visibility.processAsString())
    }

    private companion object {
        fun fillLayer(
            state: String?,
            paint: String,
            layout: String = """"visible"""",
            extra: String = "",
        ): FillLayer {
            val stateBlock = state?.let { """"state":$it,""" }.orEmpty()
            val style = decodeStyle(
                """{"version":8,${stateBlock}"sources":{},"layers":[{"id":"l","type":"fill",""" +
                        """"source":"s","layout":{"visibility":$layout},"paint":{$paint}$extra}]}"""
            )
            return style.layers[0] as FillLayer
        }
    }
}
