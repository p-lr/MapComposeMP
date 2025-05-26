package ovh.plrapps.mapcompose.maplibre.spec.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import mapcompose_mp.maplibre_rasterizer.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.data.getMapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.spec.style.props.Expr
import ovh.plrapps.mapcompose.maplibre.spec.style.props.ExpressionOrValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TestParseStyleBright {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_bright correct parsed`() = runComposeUiTest {
        var simpleStyle: MapLibreConfiguration? = null

        setContent {
            val style by produceState<MapLibreConfiguration?>(null) {
                value = Res.readBytes("files/test_style_bright.json").decodeToString().let { source ->
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
        assertEquals("Bright", style.name)
        assertEquals(listOf(0.0, 0.0), style.center)
        assertEquals(1f, style.zoom)
        assertEquals(0, style.bearing)
        assertEquals(0, style.pitch)

        
        val sources = style.sources
        assertNotNull(sources)
        assertEquals(1, sources.size)

        val openmaptiles = sources["openmaptiles"]
        assertNotNull(openmaptiles)
        assertEquals("vector", openmaptiles.type)
        assertEquals(0, openmaptiles.minzoom)
        assertEquals(14, openmaptiles.maxzoom)
        assertNotNull(openmaptiles.tiles)
        assertTrue(openmaptiles.tiles!!.isNotEmpty())
        assertNotNull(openmaptiles.attribution)
        assertTrue(openmaptiles.attribution!!.contains("MapTiler"))
        assertTrue(openmaptiles.attribution!!.contains("OpenStreetMap"))

        assertEquals("https://openmaptiles.github.io/osm-bright-gl-style/sprite", style.sprites.firstOrNull()?.url)
        assertEquals("https://api.maptiler.com/fonts/{fontstack}/{range}.pbf?key={key}", style.glyphs)

        
        val layers = style.layers
        assertNotNull(layers)
        assertTrue(layers.isNotEmpty())

        val backgroundLayer = layers.find { it.id == "background" } as BackgroundLayer
        assertNotNull(backgroundLayer)
        assertEquals("background", backgroundLayer.type)
        val bgColor = backgroundLayer.paint?.backgroundColor?.process()
        assertEquals(Color(0xFFF8F4F0), bgColor)

        val landuseLayer = layers.find { it.id == "landuse-residential" } as FillLayer
        assertNotNull(landuseLayer)
        assertEquals("fill", landuseLayer.type)
        assertEquals("openmaptiles", landuseLayer.source)
        assertEquals("landuse", landuseLayer.sourceLayer)
        
        val landuseColor = landuseLayer.paint?.fillColor
        assertNotNull(landuseColor)
        assertTrue(landuseColor is ExpressionOrValue.Expression)
        val interpolateExpr = landuseColor as ExpressionOrValue.Expression<Color>
        assertTrue(interpolateExpr.expr is Expr.Interpolate)
        val landuseStops = interpolateExpr.expr.stops
        assertEquals(2, landuseStops.size)
        assertEquals(Pair(12.0, Expr.Constant(Color.hsl(30f, 0.19f, 0.9f, 0.4f))), landuseStops[0])
        assertEquals(Pair(16.0, Expr.Constant(Color.hsl(30f, 0.19f, 0.9f, 0.2f))), landuseStops[1])

        val waterwayLayer = layers.find { it.id == "waterway_tunnel" } as LineLayer
        assertNotNull(waterwayLayer)
        assertEquals("line", waterwayLayer.type)
        assertEquals("openmaptiles", waterwayLayer.source)
        assertEquals("waterway", waterwayLayer.sourceLayer)
        
        val lineWidth = waterwayLayer.paint?.lineWidth
        assertNotNull(lineWidth)
        assertTrue(lineWidth is ExpressionOrValue.Expression)
        val lineWidthExpr = lineWidth as ExpressionOrValue.Expression<Double>
        assertTrue(lineWidthExpr.expr is Expr.Interpolate)
        val waterwayStops = lineWidthExpr.expr.stops
        assertEquals(2, waterwayStops.size)
        assertEquals(Pair(13.0, Expr.Constant(0.5)), waterwayStops[0])
        assertEquals(Pair(20.0, Expr.Constant(6.0)), waterwayStops[1])
        
        val lineColor = waterwayLayer.paint?.lineColor?.process()
        assertEquals(Color(0xFFA0C8F0), lineColor)
    }
}
