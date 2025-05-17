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
class TestParseStyleStreetV2 {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_streetV2 correct parsed`() = runComposeUiTest {
        var styleStreetV2: MapLibreConfiguration? = null

        setContent {
            val style by produceState<MapLibreConfiguration?>(null) {
                value = Res.readBytes("files/test_style_street_v2.json").decodeToString().let { source ->
                    getMapLibreConfiguration(source).getOrThrow()
                }
            }
            styleStreetV2 = style
        }

        waitUntil(timeoutMillis = 5000) {
            styleStreetV2 != null
        }

        val style = styleStreetV2!!.style

        assertEquals(8, style.version)
        assertEquals("streets-v2", style.id)
        assertEquals("Streets", style.name)

        val sources = style.sources
        assertNotNull(sources)
        assertEquals(2, sources.size)
        
        val maptilerAttribution = sources["maptiler_attribution"]
        assertNotNull(maptilerAttribution)
        assertEquals("vector", maptilerAttribution.type)
        assertTrue(maptilerAttribution.attribution?.contains("MapTiler") == true)
        
        val maptilerPlanet = sources["maptiler_planet"]
        assertNotNull(maptilerPlanet)
        assertEquals("vector", maptilerPlanet.type)
        assertEquals(0, maptilerPlanet.minzoom)
        assertEquals(15, maptilerPlanet.maxzoom)
        assertTrue(maptilerPlanet.tiles?.first()?.contains("api.maptiler.com") == true)

        val layers = style.layers
        assertNotNull(layers)
        assertTrue(layers.isNotEmpty())

        val backgroundLayer = layers.find { it.id == "Background" } as BackgroundLayer
        assertNotNull(backgroundLayer)
        assertEquals("background", backgroundLayer.type)
        val bgColor = backgroundLayer.paint?.backgroundColor
        assertNotNull(bgColor)
        assertTrue(bgColor is ExpressionOrValue.Expression)
        val bgColorExpr = bgColor
        assertTrue(bgColorExpr.expr is Expr.Interpolate)
        val bgStops = bgColorExpr.expr.stops
        assertEquals(2, bgStops.size)
        assertEquals(Pair(6.0, Expr.Constant(Color.hsl( 47F, 0.79F, 0.94F))), bgStops[0])
        assertEquals(Pair(14.0, Expr.Constant(Color.hsl(42F, 0.49f, 0.93f))), bgStops[1])

        val meadowLayer = layers.find { it.id == "Meadow" } as FillLayer
        assertNotNull(meadowLayer)
        assertEquals("fill", meadowLayer.type)
        assertEquals("maptiler_planet", meadowLayer.source)
        assertEquals("globallandcover", meadowLayer.sourceLayer)
        assertEquals(8.toDouble(), meadowLayer.maxzoom)
        val fillColor = meadowLayer.paint?.fillColor
        assertNotNull(fillColor)
        assertTrue(fillColor is ExpressionOrValue.Value<Color>)
        assertEquals(Color.hsl(75f,0.51f,0.85f), fillColor.value)
        val meadowColor = fillColor.process(null, null)
        assertEquals(Color.hsl(hue = 75F, saturation = 0.51f, lightness = 0.85f), meadowColor)
        val meadowOpacity = meadowLayer.paint?.fillOpacity
        assertNotNull(meadowOpacity)
        assertTrue(meadowOpacity is ExpressionOrValue.Expression)
        val meadowOpacityExpr = meadowOpacity as ExpressionOrValue.Expression<*>
        assertTrue(meadowOpacityExpr.expr is Expr.Interpolate)
        val meadowOpacityStops = meadowOpacityExpr.expr.stops
        assertEquals(2, meadowOpacityStops.size)
        assertEquals(Pair(0.0, Expr.Constant(1.0)), meadowOpacityStops[0])
        assertEquals(Pair(8.0, Expr.Constant(0.1)), meadowOpacityStops[1])

        val forestLayer = layers.find { it.id == "Forest" } as FillLayer
        assertNotNull(forestLayer)
        assertEquals("fill", forestLayer.type)
        val forestFilter = forestLayer.filter
        assertNotNull(forestFilter)
    }
}
