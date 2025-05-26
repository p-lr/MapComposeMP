package ovh.plrapps.mapcompose.maplibre.data

import ovh.plrapps.mapcompose.maplibre.spec.style.MapLibreStyle

data class MapLibreConfiguration(
    val style: MapLibreStyle,
    val tileSources: Map<String, MapLibreTileSource>,
    val spriteManager: SpriteManager?,
    val enableDebugSymbolsBoundingBox: Boolean = false,
    val enableDebugTileGrid: Boolean = true,
    val collisionDetectionEnabled: Boolean = true,
    val lang: LanguageCode? = LanguageCode.English
)
