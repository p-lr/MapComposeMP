package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostic

data class MapLibreConfiguration(
    val style: MapLibreStyle,
    val tileSources: Map<String, MapLibreTileSource>,
    val spriteManager: SpriteManager?,
    val collisionDetectionEnabled: Boolean = true,
    val lang: LanguageCode? = LanguageCode.English,
    /**
     * Expression and filter problems found while parsing the style.
     *
     * Parsing is deliberately lenient — a property or filter that fails to compile is skipped
     * rather than aborting the whole style — so this is where those failures surface.
     */
    val diagnostics: List<StyleDiagnostic> = emptyList(),
)
