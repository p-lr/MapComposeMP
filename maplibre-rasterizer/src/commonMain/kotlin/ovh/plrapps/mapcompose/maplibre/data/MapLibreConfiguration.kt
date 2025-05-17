package ovh.plrapps.mapcompose.maplibre.data

import ovh.plrapps.mapcompose.maplibre.spec.style.MapLibreStyle

data class MapLibreConfiguration(
    val style: MapLibreStyle,
    val tileSources: Map<String, MapLibreTileSource>,
)
