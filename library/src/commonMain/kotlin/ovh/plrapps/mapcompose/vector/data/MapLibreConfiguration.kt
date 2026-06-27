package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle

data class MapLibreConfiguration(
    val style: MapLibreStyle,
    val tileSources: Map<String, MapLibreTileSource>,
    val spriteManager: SpriteManager?,
    val collisionDetectionEnabled: Boolean = true,
    val lang: LanguageCode? = LanguageCode.English
)
