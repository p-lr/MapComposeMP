package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.getMapLibreConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import kotlinx.io.RawSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import mapcompose_mp.library.generated.resources.Res
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

@OptIn(ExperimentalTestApi::class)
class TestParseStyleSimple {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_simple correct parsed`() = runComposeUiTest {
        var simpleStyle: MapLibreConfiguration? = null
        val loadResource: suspend (String) -> RawSource? = { null }

        setContent {
            val style by produceState<MapLibreConfiguration?>(null) {
                value = Res.readBytes("files/test_style_simple.json").decodeToString().let { source ->
                    getMapLibreConfiguration(style = source, loadResource = loadResource).getOrThrow()
                }
            }
            simpleStyle = style
        }

        waitUntil(timeoutMillis = 5000) {
            simpleStyle != null
        }

        val style = simpleStyle!!.style

        assertEquals(8, style.version)
        assertEquals("MapLibre", style.name)
        assertEquals(listOf(17.65431710431244, 32.954120326746775), style.center)
        assertEquals(0f, style.zoom)
        assertEquals(0.0, style.bearing)
        assertEquals(0.0, style.pitch)

        val sources = style.sources
        assertNotNull(sources)
        assertEquals(1, sources.size)
        val maplibreSource = sources["maplibre"]
        assertNotNull(maplibreSource)
        assertEquals("vector", maplibreSource.type)

        val layers = style.layers
        assertNotNull(layers)
        assertTrue(layers.isNotEmpty())

        val backgroundLayer = layers.find { it.id == "background" } as BackgroundLayer
        assertNotNull(backgroundLayer)
        assertEquals("background", backgroundLayer.type)
        val bgColor = backgroundLayer.paint?.backgroundColor?.process()
        println("backgroundLayer.paint?.backgroundColor?.process() = $bgColor")
        assertEquals(Color(0xFFD8F2FF), bgColor)

        val coastlineLayer = layers.find { it.id == "coastline" } as LineLayer
        assertNotNull(coastlineLayer)
        assertEquals("line", coastlineLayer.type)
        val lineWidth = coastlineLayer.paint?.lineWidth
        assertNotNull(lineWidth)
        assertTrue(lineWidth is ExpressionOrValue.Expression)
        assertEquals(listOf(0.0, 6.0, 14.0, 22.0), lineWidth.expression.zoomStops)
        assertEquals(2.0, lineWidth.processAsDouble(zoom = 0.0))
        assertEquals(6.0, lineWidth.processAsDouble(zoom = 6.0))
        assertEquals(9.0, lineWidth.processAsDouble(zoom = 14.0))
        assertEquals(18.0, lineWidth.processAsDouble(zoom = 22.0))
        val coastColor = coastlineLayer.paint.lineColor?.process()
        println("coastlineLayer.paint?.lineColor?.process() = $coastColor")
        assertEquals(Color(0xFF198EC8), coastColor)
        assertEquals(0.5, coastlineLayer.paint.lineBlur?.process())

        val countriesFillLayer = layers.find { it.id == "countries-fill" } as FillLayer
        assertNotNull(countriesFillLayer)
        val fillColor = countriesFillLayer.paint?.fillColor
        assertNotNull(fillColor)
        assertTrue(fillColor is ExpressionOrValue.Expression)
        // A data-driven ["match", ["get", "ADM0_A3"], ...]: assert what it resolves to.
        assertEquals(
            Color(0xFFD6C7FF),
            fillColor.processAsColor(EvalFeature(type = "Polygon", properties = mapOf("ADM0_A3" to "ARM"))),
        )
        assertEquals(
            Color(0xFFEAB38F),
            fillColor.processAsColor(EvalFeature(type = "Polygon", properties = mapOf("ADM0_A3" to "ZZZ"))),
        )

        val geolinesLayer = layers.find { it.id == "geolines" } as LineLayer
        assertNotNull(geolinesLayer)

        val countriesLabelLayer = layers.find { it.id == "countries-label" } as SymbolLayer
        println("countriesLabelLayer = $countriesLabelLayer")
        assertNotNull(countriesLabelLayer)
        val textField = countriesLabelLayer.layout?.textField
        println("textField = $textField")
        assertNotNull(textField)
        assertTrue(textField is ExpressionOrValue.Expression)
        // A legacy {stops} function on a string property becomes a `step`, not an `interpolate`.
        assertEquals("{ABBREV}", textField.processAsString(zoom = 2.0))
        assertEquals("{ABBREV}", textField.processAsString(zoom = 3.0))
        assertEquals("{NAME}", textField.processAsString(zoom = 4.0))
        assertEquals("{NAME}", textField.processAsString(zoom = 5.0))
    }
}
