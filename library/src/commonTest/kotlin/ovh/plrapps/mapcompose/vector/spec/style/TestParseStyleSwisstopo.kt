package ovh.plrapps.mapcompose.vector.spec.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.ExperimentalResourceApi
import mapcompose_mp.library.generated.resources.Res
import ovh.plrapps.mapcompose.vector.data.json

@OptIn(ExperimentalTestApi::class)
class TestParseStyleSwisstopo {

    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `style_swisstopo parses without error`() = runComposeUiTest {
        var style: MapLibreStyle? = null

        setContent {
            val result by produceState<MapLibreStyle?>(null) {
                val content = Res.readBytes("files/test_style_swisstopo.json").decodeToString()
                value = json.decodeFromString<MapLibreStyle>(content)
            }
            style = result
        }

        waitUntil(timeoutMillis = 5000) { style != null }

        val layers = style!!.layers
        assertTrue(layers.isNotEmpty())

        // Exercise every filter: verifies both deserialization and evaluation don't throw
        for (layer in layers) {
            layer.filter?.process(emptyMap(), 10.0)
        }
    }
}
