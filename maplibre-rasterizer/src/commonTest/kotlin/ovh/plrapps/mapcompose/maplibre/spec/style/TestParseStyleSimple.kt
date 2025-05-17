package ovh.plrapps.mapcompose.maplibre.spec.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.getMapLibreConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.Color
import mapcompose_mp.maplibre_rasterizer.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.spec.style.props.Expr
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue

@OptIn(ExperimentalTestApi::class)
class TestParseStyleSimple {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_simple correct parsed`() = runComposeUiTest {
        var simpleStyle: MapLibreConfiguration? = null

        setContent {
            val style by produceState<MapLibreConfiguration?>(null) {
                value = Res.readBytes("files/test_style_simple.json").decodeToString().let { source ->
                    getMapLibreConfiguration(source).getOrThrow()
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
        assertEquals(0, style.bearing)
        assertEquals(0, style.pitch)

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
        val lineWidthExpr = lineWidth
        assertTrue(lineWidthExpr.expr is Expr.Interpolate)
        val stops = lineWidthExpr.expr.stops
        assertEquals(4, stops.size)
        assertEquals(Pair(0.0, Expr.Constant(2.0)), stops[0])
        assertEquals(Pair(6.0, Expr.Constant(6.0)), stops[1])
        assertEquals(Pair(14.0, Expr.Constant(9.0)), stops[2])
        assertEquals(Pair(22.0, Expr.Constant(18.0)), stops[3])
        val coastColor = coastlineLayer.paint?.lineColor?.process()
        println("coastlineLayer.paint?.lineColor?.process() = $coastColor")
        assertEquals(Color(0xFF198EC8), coastColor)
        assertEquals(0.5, coastlineLayer.paint?.lineBlur?.process())

        val countriesFillLayer = layers.find { it.id == "countries-fill" } as FillLayer
        assertNotNull(countriesFillLayer)
        val fillColor = countriesFillLayer.paint?.fillColor
        assertNotNull(fillColor)
        assertTrue(fillColor is ExpressionOrValue.Expression)
        val matchExpr = fillColor as ExpressionOrValue.Expression<Color>
        assertTrue(matchExpr.expr is Expr.Match)
        val match = matchExpr.expr as Expr.Match<Color>
        assertNotNull(match.input)
        assertTrue(match.input is Expr.Get<*>)
        assertEquals("ADM0_A3", ((match.input as Expr.Get<*>).property as Expr.Constant).value)
        assertTrue(match.branches.isNotEmpty())
        val firstBranch = match.branches[0]
        println("firstBranch.first = ${firstBranch.first}")
        println("firstBranch.second = ${firstBranch.second}")
        println("firstBranch.second::class = ${firstBranch.second::class}")
        assertTrue((firstBranch.first as List<*>).map { it.toString() }.contains("ARM"))
        assertEquals(Color(0xFFD6C7FF), (firstBranch.second as Expr.Constant<Color>).value)
        assertEquals(Color(0xFFEAB38F), (match.elseExpr as Expr.Constant<Color>).value)

        val geolinesLayer = layers.find { it.id == "geolines" } as LineLayer
        assertNotNull(geolinesLayer)

        val countriesLabelLayer = layers.find { it.id == "countries-label" } as SymbolLayer
        println("countriesLabelLayer = $countriesLabelLayer")
        assertNotNull(countriesLabelLayer)
        val textField = countriesLabelLayer.layout?.textField
        println("textField = $textField")
        assertNotNull(textField)
        assertTrue(textField is ExpressionOrValue.Expression)
        val textFieldExpr = textField
        println("textFieldExpr.expr = ${textFieldExpr.expr}")
        assertTrue(textFieldExpr.expr is Expr.Interpolate)

        val textFieldStops = textFieldExpr.expr.stops
        assertEquals(2, textFieldStops.size)
        assertEquals(Pair(2.0, Expr.Constant("{ABBREV}")), textFieldStops[0])
        assertEquals(Pair(4.0, Expr.Constant("{NAME}")), textFieldStops[1])
    }
}
