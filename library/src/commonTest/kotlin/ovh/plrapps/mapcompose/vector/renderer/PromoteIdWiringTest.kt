package ovh.plrapps.mapcompose.vector.renderer

import kotlinx.serialization.builtins.serializer
import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.json
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.PromoteId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import ovh.plrapps.mapcompose.vector.spec.style.serializers.ExpressionOrValueSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring that makes a source's `promoteId` reach `["id"]`.
 *
 * `buildEvalFeature` used to hand the expression engine `feature.id?.toDouble()` and nothing else,
 * so the property was parsed and dropped -- the finding this pins.
 *
 * The asymmetry the second region pins is upstream's own, not a divergence of this port.
 * `worker_tile.ts` computes the promoted id with `FeatureIndex.getId`, but every bucket's
 * `populate` filters against `toEvaluationFeature(feature, needGeometry)`
 * (`src/data/evaluation_feature.ts`), which keeps the raw protobuf `feature.id`; only the
 * `BucketFeature` handed to paint and layout carries the promoted one.
 */
class PromoteIdWiringTest {

    private class TestRenderer(promoteIds: Map<String, PromoteId>) : BaseRenderer(
        configuration = MapLibreConfiguration(
            style = MapLibreStyle(),
            tileSources = emptyMap(),
            spriteManager = null,
            promoteIds = promoteIds,
        )
    )

    /** `ref` is a string, `n` a number and `flag` a boolean, so all three arms are covered. */
    private val feature = Mvt.pointFeature(10 to 10, id = 7L, tags = listOf(0, 0, 1, 1, 2, 2))

    private fun tileLayer(name: String = "roads") = Mvt.layer(
        name = name,
        features = listOf(feature),
        keys = listOf("ref", "n", "flag"),
        values = listOf(
            Mvt.stringValue("A1"),
            Mvt.numberValue(12.0),
            Tile.Value(boolValue = true),
        ),
    )

    private fun renderer(promoteId: PromoteId?) =
        TestRenderer(promoteId?.let { mapOf("basemap" to it) } ?: emptyMap())

    /** Built the way `TileRenderer` and `SymbolBucketBuilder` build it, source name included. */
    private fun evalFeature(promoteId: PromoteId?, sourceLayer: String = "roads"): EvalFeature {
        val renderer = renderer(promoteId)
        val tileLayer = tileLayer(sourceLayer)
        return renderer.buildEvalFeature(
            feature,
            tileLayer,
            canonical = null,
            promoteIdProperty = renderer.promoteIdPropertyFor("basemap", tileLayer),
        )
    }

    private fun layerOf(styleJson: String): Layer =
        json.decodeFromString(Layer.serializer(), styleJson)

    // region the promoted id

    @Test
    fun `a promoted string property becomes the feature id`() {
        assertEquals("A1", evalFeature(PromoteId.Single("ref")).id)
    }

    @Test
    fun `a promoted number property stays a number`() {
        assertEquals(12.0, evalFeature(PromoteId.Single("n")).id)
    }

    /** Upstream's one coercion: `if (typeof id === 'boolean') id = Number(id)`. */
    @Test
    fun `a promoted boolean is coerced to a number`() {
        assertEquals(1.0, evalFeature(PromoteId.Single("flag")).id)
    }

    @Test
    fun `a source promoting nothing keeps the protobuf id`() {
        assertEquals(7.0, evalFeature(promoteId = null).id)
    }

    /** Upstream replaces the id, it does not fall back to it -- `getId` assigns unconditionally. */
    @Test
    fun `a promoteId naming an absent property leaves the id null`() {
        assertNull(evalFeature(PromoteId.Single("absent")).id)
    }

    @Test
    fun `the object form applies only to the source layer it names`() {
        val promoteId = PromoteId.PerSourceLayer(mapOf("roads" to "ref"))
        assertEquals("A1", evalFeature(promoteId, sourceLayer = "roads").id)
        assertEquals(7.0, evalFeature(promoteId, sourceLayer = "water").id)
    }

    @Test
    fun `the promoted property is still readable through get`() {
        assertEquals("A1", evalFeature(PromoteId.Single("ref")).properties["ref"])
    }

    /** The id reaches the engine, not merely the feature. */
    @Test
    fun `an id expression in a paint property reads the promoted id`() {
        val expression = json.decodeFromString(
            ExpressionOrValueSerializer(String.serializer()),
            """["to-string", ["id"]]""",
        )
        assertEquals("A1", expression.processAsString(evalFeature(PromoteId.Single("ref"))))
        assertEquals("7", expression.processAsString(evalFeature(promoteId = null)))
    }

    // endregion

    // region the upstream asymmetry

    private fun filterMatches(filterJson: String, promoteId: PromoteId?): Boolean {
        val renderer = renderer(promoteId)
        val tileLayer = tileLayer()
        val styleLayer = layerOf(
            """{"id":"l","type":"fill","source":"basemap","source-layer":"roads","filter":$filterJson}"""
        )
        return renderer.shouldRenderFeature(
            feature, tileLayer, styleLayer, zoom = 0.0,
            evalFeature = evalFeature(promoteId),
        )
    }

    @Test
    fun `a filter sees the raw protobuf id even when the source promotes`() {
        val promoteId = PromoteId.Single("ref")
        assertTrue(
            filterMatches("""["==", ["id"], 7]""", promoteId),
            "upstream's toEvaluationFeature keeps feature.id, so a filter matches the raw 7",
        )
        assertFalse(filterMatches("""["==", ["id"], "A1"]""", promoteId))
    }

    @Test
    fun `the legacy id filter sees the raw protobuf id too`() {
        val promoteId = PromoteId.Single("ref")
        assertTrue(filterMatches("""["==", "${'$'}id", 7]""", promoteId))
        assertFalse(filterMatches("""["==", "${'$'}id", "A1"]""", promoteId))
    }

    @Test
    fun `the filter view carries the same properties geometry and tile`() {
        val promoted = evalFeature(PromoteId.Single("ref"))
        val view = assertNotNull(promoted.filterFeature)
        assertEquals(promoted.type, view.type)
        assertEquals(promoted.properties, view.properties)
        assertEquals(promoted.canonical, view.canonical)
        assertEquals(promoted.geometry.size, view.geometry.size)
        assertEquals(promoted.geometry[0][0].x, view.geometry[0][0].x)
        assertEquals(7.0, view.id)
    }

    @Test
    fun `a source promoting nothing has no filter view at all`() {
        assertNull(
            evalFeature(promoteId = null).filterFeature,
            "nearly every source promotes nothing, and must pay no allocation for this",
        )
    }

    // endregion
}
