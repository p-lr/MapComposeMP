package ovh.plrapps.mapcompose.vector.symbol

import kotlinx.serialization.json.Json
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.props.ExpressionOrValue
import ovh.plrapps.mapcompose.vector.spec.style.symbol.SymbolLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Transcription of `maplibre-gl-js/src/symbol/symbol_size.test.ts`.
 *
 * Upstream's wording and assertions are kept so a failure can be traced back to a `describe`/`test`
 * block by search. Do not rewrite these to match the port -- if one fails, the port is wrong.
 *
 * One deliberate change of *numbers*, not of behaviour: upstream packs a feature's two sizes into a
 * `Uint16` vertex attribute, so `evaluateSizeForFeature` divides them by `SIZE_PACK_FACTOR` (128)
 * and its fixtures read `lowerSize: 1280`. Sizes are plain `Double`s here with nothing to pack, so
 * the same cases pass the unpacked `10.0`.
 */
class SymbolSizeUpstreamTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createTextSizeValue(textSize: String): ExpressionOrValue<Double>? =
        json.decodeFromString(SymbolLayout.serializer(), """{"text-size":$textSize}""").textSize

    private fun getSizeData(tileZoom: Double, textSize: String): SizeData =
        getSizeData(tileZoom, createTextSizeValue(textSize), StyleSpecDefaults.TEXT_SIZE)

    // region evaluateSizeForZoom

    @Test
    fun `reports the size at the camera zoom for a bucket built past the last stop`() {
        val sizeData = getSizeData(6.0, """["interpolate", ["linear"], ["zoom"], 1, 9, 4, 17]""")

        val size = evaluateSizeForZoom(sizeData, 2.0)

        assertEquals(11.666666, size.size, 1e-5)
    }

    @Test
    fun `reports the first stop's size below the first stop`() {
        val sizeData = getSizeData(6.0, """["interpolate", ["linear"], ["zoom"], 1, 9, 4, 17]""")

        val size = evaluateSizeForZoom(sizeData, 0.0)

        assertEquals(9.0, size.size)
    }

    @Test
    fun `caps the size at the tile zoom + 1 size above the tile's own zoom range`() {
        val sizeData = getSizeData(2.0, """["interpolate", ["linear"], ["zoom"], 1, 9, 4, 17]""")

        val size = evaluateSizeForZoom(sizeData, 4.0)

        assertEquals(14.333333, size.size, 1e-5)
    }

    @Test
    fun `keeps a zoom-referencing step base finite instead of evaluating it at -Infinity`() {
        val sizeData = getSizeData(6.0, """["step", ["zoom"], ["*", ["zoom"], 2], 4, 17]""")

        val size = evaluateSizeForZoom(sizeData, 2.0)

        assertEquals(6.0, size.size)
    }

    @Test
    fun `reports how far the camera zoom sits between a composite size's covering stops`() {
        val sizeData = getSizeData(
            5.0,
            """["interpolate", ["linear"], ["zoom"], 1, ["get", "size"], 4, ["*", ["get", "size"], 2], 8, ["*", ["get", "size"], 4]]""",
        )

        val size = evaluateSizeForZoom(sizeData, 6.0)

        assertEquals(0.5, size.sizeT)
        assertEquals(0.0, size.size)
    }

    @Test
    fun `keeps a composite size at its lower covering stop below the tile's own zoom range`() {
        val sizeData = getSizeData(
            5.0,
            """["interpolate", ["linear"], ["zoom"], 1, ["get", "size"], 4, ["*", ["get", "size"], 2], 8, ["*", ["get", "size"], 4]]""",
        )

        val size = evaluateSizeForZoom(sizeData, 2.0)

        assertEquals(0.0, size.sizeT)
    }

    @Test
    fun `never blends a stepped composite size`() {
        val sizeData = getSizeData(
            5.0,
            """["step", ["zoom"], ["get", "size"], 4, ["*", ["get", "size"], 2], 8, ["*", ["get", "size"], 4]]""",
        )

        val size = evaluateSizeForZoom(sizeData, 6.0)

        assertEquals(0.0, size.sizeT)
    }

    // endregion

    // region evaluateSizeForFeature

    @Test
    fun `unpacks the feature's own size for a source size`() {
        val sizeData = getSizeData(5.0, """["get", "size"]""")

        val size = evaluateSizeForFeature(
            sizeData, EvaluatedZoomSize(size = 0.0, sizeT = 0.0), FeatureSizes(10.0, 20.0),
        )

        assertEquals(10.0, size)
    }

    @Test
    fun `blends the feature's two baked sizes for a composite size`() {
        val sizeData = getSizeData(
            5.0,
            """["interpolate", ["linear"], ["zoom"], 1, ["get", "size"], 4, ["*", ["get", "size"], 2]]""",
        )

        val size = evaluateSizeForFeature(
            sizeData, EvaluatedZoomSize(size = 0.0, sizeT = 0.5), FeatureSizes(10.0, 20.0),
        )

        assertEquals(15.0, size)
    }

    @Test
    fun `reports the zoom uniform for a camera size which has no per-feature data`() {
        val sizeData = getSizeData(5.0, """["interpolate", ["linear"], ["zoom"], 1, 9, 4, 17]""")

        val size = evaluateSizeForFeature(
            sizeData, EvaluatedZoomSize(size = 12.0, sizeT = 0.0), FeatureSizes(10.0, 20.0),
        )

        assertEquals(12.0, size)
    }

    // endregion
}
