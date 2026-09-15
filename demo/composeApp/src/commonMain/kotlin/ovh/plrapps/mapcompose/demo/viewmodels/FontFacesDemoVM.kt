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
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import ovh.plrapps.mapcompose.api.addVectorLayer
import ovh.plrapps.mapcompose.api.enableRotation
import ovh.plrapps.mapcompose.api.removeLayer
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.shouldLoopScale
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.vector.core.VectorTileStreamProvider
import kotlin.math.pow

/**
 * Shows the style specification's root `font-faces`: a font *file* the style names, downloaded and
 * rasterized locally instead of the labels coming from the `glyphs` server.
 *
 * The bundled street style is served twice, once as it is and once with a `font-faces` block naming
 * a font that looks nothing like the server's -- so the switch says at a glance which path drew what
 * is on screen. The font file is a plain TTF from Google Fonts, over a CDN, needing no key.
 */
class FontFacesDemoVM : ViewModel() {

    /** Whether the declared font file draws the labels, or the style's `glyphs` server does. */
    var useFontFile by mutableStateOf(true)
        private set

    private val maxLevel = 16
    private val mapSize = mapSizeAtLevel(maxLevel, tileSize = 256)

    private var layerId: String? = null
    private var reloadJob: Job? = null

    val state = MapState(
        levelCount = maxLevel + 1,
        fullWidth = mapSize,
        fullHeight = mapSize,
        workerCount = 16,
    ) {
        scroll(0.5228, 0.3518)
    }.apply {
        enableRotation()
        scale = 0.35
        shouldLoopScale = true
    }

    init {
        reload()
    }

    fun onUseFontFileChanged(value: Boolean) {
        if (value == useFontFile) return
        useFontFile = value
        reload()
    }

    private fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            layerId?.let { state.removeLayer(it) }
            layerId = state.addVectorLayer(
                FontFaceStyleProvider(
                    delegate = OSMVectorTileStreamProvider(styleUrl = STYLE_URL),
                    fontFaces = if (useFontFile) FONT_FACES else null,
                )
            )
        }
    }

    private companion object {
        const val STYLE_URL = "files/style_street_v2.json"

        /**
         * One file per `text-font` name the bundled style uses, covering every codepoint.
         *
         * The names are what a layer's `text-font` says; a name the style never uses would declare a
         * font nothing draws with.
         */
        val FONT_FACES = """
            "font-faces": {
              "Roboto Regular": "$FONT_URL",
              "Roboto Medium": "$FONT_URL",
              "Roboto Bold": "$FONT_URL",
              "Roboto Italic": "$FONT_URL",
              "Noto Sans Regular": "$FONT_URL"
            }
        """.trimIndent()
    }
}

/** A script face, so that a label drawn from the file cannot be mistaken for a server glyph. */
private const val FONT_URL =
    "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/pacifico/Pacifico-Regular.ttf"

/**
 * Serves the bundled style with a `font-faces` block patched into it, and delegates everything else
 * -- tiles, sprites, glyph ranges and the font file itself -- to the platform provider.
 */
private class FontFaceStyleProvider(
    private val delegate: OSMVectorTileStreamProvider,
    private val fontFaces: String?,
) : VectorTileStreamProvider {

    override val styleUrl: String = delegate.styleUrl

    override suspend fun loadResources(url: String): RawSource? {
        if (url != styleUrl || fontFaces == null) return delegate.loadResources(url)
        val style = delegate.loadResources(url)?.buffered()?.readString() ?: return null
        // The style is an object, so its first brace is the one the block belongs behind.
        return Buffer().apply { writeString(style.replaceFirst("{", "{$fontFaces,")) }
    }

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
