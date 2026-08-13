package ovh.plrapps.mapcompose.vector.spec.style

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
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import mapcompose_mp.library.generated.resources.Res
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue

@OptIn(ExperimentalTestApi::class)
class TestParseStyleStreetV2 {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_streetV2 correct parsed`() = runComposeUiTest {
        var styleStreetV2: MapLibreConfiguration? = null
        val loadResource: suspend (String) -> RawSource? = { url ->
            when {
                url.endsWith(".json") -> {
                    val resource = Res.readBytes("files/test_style_street_v2_sprite.json")
                    val buffer = Buffer()
                    buffer.write(resource)
                    buffer
                }
                url.endsWith(".png") -> {
                    val resource = Res.readBytes("files/test_style_street_v2_sprite.png")
                    val buffer = Buffer()
                    buffer.write(resource)
                    buffer
                }
                else -> null
            }
        }

        setContent {
            val style by produceState<MapLibreConfiguration?>(null) {
                value = Res.readBytes("files/test_style_street_v2.json").decodeToString().let { source ->
                    getMapLibreConfiguration(style = source, loadResource = loadResource).getOrThrow()
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
        val bgStops = bgColorExpr.expression.zoomStops!!
        assertEquals(2, bgStops.size)
        assertEquals(6.0, bgStops[0])
        assertEquals(14.0, bgStops[1])
        assertEquals(Color.hsl(47F, 0.79F, 0.94F), bgColorExpr.processAsColor(zoom = 6.0))
        assertEquals(Color.hsl(42F, 0.49f, 0.93f), bgColorExpr.processAsColor(zoom = 14.0))

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
        val meadowOpacity = meadowLayer.paint.fillOpacity
        assertNotNull(meadowOpacity)
        assertTrue(meadowOpacity is ExpressionOrValue.Expression)
        val meadowOpacityExpr = meadowOpacity as ExpressionOrValue.Expression<*>
        val meadowOpacityStops = meadowOpacityExpr.expression.zoomStops!!
        assertEquals(2, meadowOpacityStops.size)
        assertEquals(0.0, meadowOpacityStops[0])
        assertEquals(8.0, meadowOpacityStops[1])
        assertEquals(1.0, meadowOpacity.processAsDouble(zoom = 0.0))
        assertEquals(0.1, meadowOpacity.processAsDouble(zoom = 8.0))

        val forestLayer = layers.find { it.id == "Forest" } as FillLayer
        assertNotNull(forestLayer)
        assertEquals("fill", forestLayer.type)
        val forestFilter = forestLayer.filter
        assertNotNull(forestFilter)
    }
}
