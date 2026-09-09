package ovh.plrapps.mapcompose.vector.renderer

import androidx.compose.ui.graphics.Color
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.FillLayer
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.EXTENT
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsColor
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsDouble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wiring that lets `within` and `distance` see anything at all.
 *
 * Both expressions were ported and pass MapLibre's conformance fixtures, but nothing in the
 * renderer supplied them with the two things they read: the tile's canonical `(z, x, y)` and the
 * feature's decoded geometry. `Within.evaluate` opens with `ctx.canonicalID() ?: return false` and
 * `Distance.evaluate` with `?: return Double.NaN`, so a geographically gated layer silently drew
 * nothing (a filter) or fell back to its spec default (a paint property, since
 * `StyleExpression.evaluate` converts NaN to the default).
 *
 * The geometry also has to arrive in the *engine's* tile space: `EXTENT` is 8192 while an MVT layer
 * is usually 4096, and upstream's `load_geometry.ts` rescales between the two.
 */
class GeometryExpressionWiringTest {

    /** `BaseRenderer` is abstract only because every real renderer adds painting; this adds none. */
    private class TestRenderer : BaseRenderer(
        configuration = MapLibreConfiguration(
            style = MapLibreStyle(),
            tileSources = emptyMap(),
            spriteManager = null,
        )
    )

    private val renderer = TestRenderer()

    /**
     * The world in one tile, so a tile coordinate is a Mercator coordinate times [EXTENT] with no
     * offset to reason about. `Within` shifts by `canonical.x * EXTENT`, which is 0 here.
     */
    private val canonical = CanonicalTileId(z = 0, x = 0, y = 0)

    /** A square around the null island, `[-10, 10]` in both axes. */
    private val square = """
        {"type":"Polygon","coordinates":[[[-10,-10],[10,-10],[10,10],[-10,10],[-10,-10]]]}
    """.trimIndent()

    /** MVT `(2048, 2048)` at extent 4096 is the centre of the world, i.e. lng 0 / lat 0. */
    private val centre = Mvt.pointFeature(2048 to 2048)

    /** Far into the north-west corner, well outside [square]. */
    private val corner = Mvt.pointFeature(100 to 100)

    private fun layerOf(styleJson: String): Layer =
        json.decodeFromString(Layer.serializer(), styleJson)

    private fun tileLayer(vararg features: Tile.Feature): Tile.Layer =
        Mvt.layer(name = "l", features = features.toList())

    // region geometry decoding

    /**
     * The provider used to be attached only when the *filter* asked for geometry, so a `within` in
     * a paint or layout property saw an empty list — and, because `TileRenderer.localPropCache` is
     * keyed across style layers, so did a later layer whose filter did ask.
     */
    @Test
    fun geometryIsAvailableEvenWhenNoFilterAsksForIt() {
        val feature = renderer.buildEvalFeature(centre, tileLayer(centre), canonical)
        assertEquals(1, feature.geometry.size)
        assertEquals(1, feature.geometry[0].size)
    }

    /** `load_geometry.ts`: `const scale = EXTENT / feature.extent`. */
    @Test
    fun geometryIsRescaledFromTheLayerExtentToTheEngineExtent() {
        val point = Mvt.pointFeature(1024 to 512)
        val layer = Mvt.layer(name = "l", features = listOf(point), extent = 4096)
        val decoded = renderer.buildEvalFeature(point, layer, canonical).geometry[0][0]

        val scale = EXTENT.toDouble() / 4096
        assertEquals(1024 * scale, decoded.x)
        assertEquals(512 * scale, decoded.y)
    }

    /** A layer that already publishes at the engine's extent must not be scaled at all. */
    @Test
    fun geometryAtTheEngineExtentIsLeftAlone() {
        val point = Mvt.pointFeature(1024 to 512)
        val layer = Mvt.layer(name = "l", features = listOf(point), extent = EXTENT)
        val decoded = renderer.buildEvalFeature(point, layer, canonical).geometry[0][0]

        assertEquals(1024.0, decoded.x)
        assertEquals(512.0, decoded.y)
    }

    // endregion

    // region filters

    @Test
    fun aWithinFilterKeepsTheFeaturesInsideAndDropsTheOnesOutside() {
        val styleLayer = layerOf(
            """{"id":"f","type":"fill","source":"s","source-layer":"l","filter":["within",$square]}"""
        )
        val layer = tileLayer(centre, corner)

        assertTrue(
            renderer.shouldRenderFeature(
                centre, layer, styleLayer, zoom = 0.0,
                evalFeature = renderer.buildEvalFeature(centre, layer, canonical),
            )
        )
        assertFalse(
            renderer.shouldRenderFeature(
                corner, layer, styleLayer, zoom = 0.0,
                evalFeature = renderer.buildEvalFeature(corner, layer, canonical),
            )
        )
    }

    /**
     * Without a canonical id `Within` returns false for everything, which is what made a whole
     * layer disappear. Pinned so the regression is a test failure and not a blank map.
     */
    @Test
    fun aWithinFilterWithoutACanonicalIdRejectsEverything() {
        val styleLayer = layerOf(
            """{"id":"f","type":"fill","source":"s","source-layer":"l","filter":["within",$square]}"""
        )
        val layer = tileLayer(centre)

        assertFalse(
            renderer.shouldRenderFeature(
                centre, layer, styleLayer, zoom = 0.0,
                evalFeature = renderer.buildEvalFeature(centre, layer, canonical = null),
            )
        )
    }

    /** The feature's own id wins, so a call site cannot drop it by passing none. */
    @Test
    fun theFeatureCarriesItsCanonicalIdIntoTheFilter() {
        val styleLayer = layerOf(
            """{"id":"f","type":"fill","source":"s","source-layer":"l","filter":["within",$square]}"""
        )
        val layer = tileLayer(centre)
        val evalFeature = renderer.buildEvalFeature(centre, layer, canonical)

        assertEquals(canonical, evalFeature.canonical)
        assertTrue(
            renderer.shouldRenderFeature(
                centre, layer, styleLayer, zoom = 0.0,
                evalFeature = evalFeature,
                canonical = null,
            )
        )
    }

    // endregion

    // region paint properties

    /**
     * Upstream forwards `canonical` to `populatePaintArrays` as well as to the filter
     * (`circle_bucket.ts`), so a `within` decides a paint value there. Here it rides on the
     * `EvalFeature`, which is what every `processAs*` helper already receives.
     */
    @Test
    fun aWithinInAPaintPropertySelectsTheBranch() {
        val styleLayer = layerOf(
            """
            {"id":"f","type":"fill","source":"s","source-layer":"l",
             "paint":{"fill-color":["case",["within",$square],"#ff0000","#0000ff"]}}
            """.trimIndent()
        ) as FillLayer
        val fillColor = assertNotNull(styleLayer.paint.fillColor)
        val layer = tileLayer(centre, corner)

        assertEquals(
            Color(0xFFFF0000),
            fillColor.processAsColor(renderer.buildEvalFeature(centre, layer, canonical), 0.0),
        )
        assertEquals(
            Color(0xFF0000FF),
            fillColor.processAsColor(renderer.buildEvalFeature(corner, layer, canonical), 0.0),
        )
    }

    /**
     * `distance` returned NaN for want of a canonical id, and `StyleExpression.evaluate` turns a NaN
     * into the property default without a warning — a silent wrong value rather than a visible one.
     */
    @Test
    fun aDistanceInAPaintPropertyIsFiniteAndGrowsWithSeparation() {
        val styleLayer = layerOf(
            """
            {"id":"f","type":"fill","source":"s","source-layer":"l",
             "paint":{"fill-opacity":["distance",{"type":"Point","coordinates":[0,0]}]}}
            """.trimIndent()
        ) as FillLayer
        val distance = assertNotNull(styleLayer.paint.fillOpacity)
        val layer = tileLayer(centre, corner)

        val atCentre = assertNotNull(
            distance.processAsDouble(renderer.buildEvalFeature(centre, layer, canonical), 0.0)
        )
        val atCorner = assertNotNull(
            distance.processAsDouble(renderer.buildEvalFeature(corner, layer, canonical), 0.0)
        )

        assertFalse(atCentre.isNaN(), "distance at the null island should not be NaN")
        assertFalse(atCorner.isNaN(), "distance in the corner should not be NaN")
        assertTrue(atCentre < 1.0, "the feature sits on the reference point, got $atCentre m")
        assertTrue(atCorner > 1_000_000.0, "the corner is a continent away, got $atCorner m")
    }

    // endregion
}
