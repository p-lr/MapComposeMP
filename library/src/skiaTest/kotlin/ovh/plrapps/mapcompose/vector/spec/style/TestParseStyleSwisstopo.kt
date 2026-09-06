package ovh.plrapps.mapcompose.vector.spec.style

import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.ExperimentalResourceApi
import mapcompose_mp.library.generated.resources.Res
import ovh.plrapps.mapcompose.vector.data.json

class TestParseStyleSwisstopo {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_swisstopo parses without error`() = runTest {
        val content = Res.readBytes("files/test_style_swisstopo.json").decodeToString()
        val style = json.decodeFromString<MapLibreStyle>(content)

        val layers = style.layers
        assertTrue(layers.isNotEmpty())

        // Exercise every filter: verifies both deserialization and evaluation don't throw
        for (layer in layers) {
            layer.filter?.filter?.filter(GlobalProperties(zoom = 10.0), EvalFeature(type = "LineString"))
        }
    }
}
