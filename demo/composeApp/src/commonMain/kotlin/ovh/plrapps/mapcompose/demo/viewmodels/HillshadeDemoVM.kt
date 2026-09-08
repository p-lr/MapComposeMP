package ovh.plrapps.mapcompose.demo.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.writeString
import ovh.plrapps.mapcompose.api.addVectorLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.removeLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.ui.layout.Forced
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import kotlin.math.pow

/**
 * One preset of `hillshade`'s newer controls.
 *
 * `multidirectional` is the reason the illumination properties are arrays at all, so its preset
 * declares four lights -- which is also the input that used to fail to load, back when the
 * properties were typed as scalars.
 */
enum class HillshadePreset(
    val label: String,
    val method: String,
    val directions: List<Double>,
    val altitudes: List<Double>,
) {
    Standard("standard", "standard", listOf(335.0), listOf(45.0)),
    Basic("basic", "basic", listOf(335.0), listOf(45.0)),
    Combined("combined", "combined", listOf(335.0), listOf(45.0)),
    Igor("igor", "igor", listOf(335.0), listOf(45.0)),
    Multidirectional(
        "multidirectional",
        "multidirectional",
        listOf(315.0, 45.0, 135.0, 225.0),
        listOf(60.0, 60.0, 60.0, 60.0),
    ),
}

/**
 * Shows the `hillshade` layer over a `raster-dem` source.
 *
 * The elevation comes from AWS Terrain Tiles, which are Terrarium-encoded and need no API key. The
 * style bundles nothing but a background and the hillshade itself, so what is on screen is entirely
 * the relief -- which is the point of the screen. It starts over the Alps.
 *
 * The style is built here rather than read from `files/style_hillshade.json`, so that the five
 * `hillshade-method` algorithms can be compared on real terrain: switching one swaps the vector
 * layer for a freshly parsed style.
 */
class HillshadeDemoVM : ViewModel() {

    var preset by mutableStateOf(HillshadePreset.Standard)
        private set

    var altitude by mutableStateOf(45.0)
        private set

    private val maxLevel = 13
    private val minLevel = 4
    private val tileSize = 256
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = tileSize)

    private var layerId: String? = null
    private var reloadJob: Job? = null

    val state = MapState(
        levelCount = maxLevel + 1,
        fullWidth = mapSize,
        fullHeight = mapSize,
        workerCount = 16,
        tileSize = tileSize,
    ) {
        minimumScaleMode(Forced(1 / 2.0.pow(maxLevel - minLevel)))
        scroll(0.5231943373, 0.3542287256)
    }.apply {
        enableRotation()
        scale = 0.0
        shouldLoopScale = true
    }

    init {
        reload()
    }

    fun onPresetSelected(value: HillshadePreset) {
        if (value == preset) return
        preset = value
        altitude = value.altitudes.first()
        reload()
    }

    /** Tracks the slider while it is being dragged; rebuilding the style per pixel is pointless. */
    fun onAltitudeChanged(value: Double) {
        altitude = value
    }

    /** Called when the drag ends, which is when the style is worth reparsing. */
    fun onAltitudeCommitted() {
        reload()
    }

    private fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            layerId?.let { state.removeLayer(it) }
            layerId = state.addVectorLayer(
                HillshadeStyleProvider(
                    delegate = OSMVectorTileStreamProvider(styleUrl = STYLE_URL),
                    style = styleJson(),
                )
            )
        }
    }

    /**
     * The demo style, with the current preset patched in.
     *
     * Every altitude is set to the slider's value, so a `multidirectional` preset keeps its four
     * lights at one height -- the four directions are what it is there to show.
     */
    private fun styleJson(): String {
        val directions = preset.directions.joinToString(prefix = "[", postfix = "]")
        val altitudes = preset.altitudes
            .joinToString(prefix = "[", postfix = "]") { altitude.toString() }
        return """
            {
              "version": 8,
              "name": "Terrain hillshade",
              "sources": {
                "terrain": {
                  "type": "raster-dem",
                  "encoding": "terrarium",
                  "tiles": ["https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"],
                  "minzoom": 0,
                  "maxzoom": 15,
                  "attribution": "Terrain Tiles (AWS Open Data), Mapzen"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#f4f1ea" }
                },
                {
                  "id": "hillshade",
                  "type": "hillshade",
                  "source": "terrain",
                  "paint": {
                    "hillshade-exaggeration": 0.6,
                    "hillshade-method": "${preset.method}",
                    "hillshade-illumination-direction": $directions,
                    "hillshade-illumination-altitude": $altitudes,
                    "hillshade-shadow-color": "#4a3f35",
                    "hillshade-highlight-color": "#ffffff",
                    "hillshade-accent-color": "#5a5148"
                  }
                }
              ]
            }
        """.trimIndent()
    }

    private companion object {
        /* The bundled style is no longer read, but the platform provider still resolves relative
         * resource URLs against it -- and this one has neither sprites nor glyphs to resolve. */
        const val STYLE_URL = "files/style_hillshade.json"
    }
}

/**
 * Serves an in-memory style and delegates everything else -- tiles included -- to the platform
 * provider.
 */
private class HillshadeStyleProvider(
    private val delegate: OSMVectorTileStreamProvider,
    private val style: String,
) : VectorTileStreamProvider {

    override val styleUrl: String = delegate.styleUrl

    override suspend fun loadResources(url: String): RawSource? =
        if (url == styleUrl) Buffer().apply { writeString(style) } else delegate.loadResources(url)

    override suspend fun getTileStream(
        tileUrl: String,
        row: Int,
        col: Int,
        zoomLvl: Int,
    ): RawSource? = delegate.getTileStream(tileUrl, row, col, zoomLvl)
}

/**
 * wmts level are 0 based.
 * At level 0, the map corresponds to just one tile.
 */
private fun mapSizeAtLevel(wmtsLevel: Int, tileSize: Int): Int {
    return tileSize * 2.0.pow(wmtsLevel).toInt()
}
