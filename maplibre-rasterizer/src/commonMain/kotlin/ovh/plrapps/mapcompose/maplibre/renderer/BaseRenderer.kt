package ovh.plrapps.mapcompose.maplibre.renderer

import ovh.plrapps.mapcompose.maplibre.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.maplibre.spec.Tile
import ovh.plrapps.mapcompose.maplibre.spec.style.Layer

abstract class BaseRenderer(
    private val configuration: MapLibreConfiguration,
) {

    fun shouldRenderFeature(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
        styleLayer: Layer,
        zoom: Double
    ): Boolean {
        val filter = styleLayer.filter
        if (filter == null) {
            return true
        }
        return filter.process(
            featureProperties = extractFeatureProperties(feature, tileLayer),
            zoom = zoom.toInt().toDouble()
        )
    }

    private val MAX_ZOOM = 30.0

    fun isZoomInRange(styleLayer: Layer, zoom: Double): Boolean {
        val minZoom = styleLayer.minzoom ?: 0.0
        val maxZoom = styleLayer.maxzoom ?: MAX_ZOOM
        return zoom in minZoom..maxZoom
    }

    fun extractFeatureProperties(feature: Tile.Feature, tileLayer: Tile.Layer): Map<String, Any?> {
        val props = mutableMapOf<String, Any?>()
        val keys = tileLayer.keys
        val values = tileLayer.values
        for (i in feature.tags.indices step 2) {
            val keyIdx = feature.tags[i]
            val valueIdx = feature.tags[i + 1]
            if (keyIdx < keys.size && valueIdx < values.size) {
                val key = keys[keyIdx]
                val value = values[valueIdx]
                props[key] =
                    value.stringValue ?: value.floatValue ?: value.doubleValue ?: value.intValue ?: value.uintValue
                            ?: value.sintValue ?: value.boolValue
            }
        }

        props["\$type"] = when (feature.type) {
            Tile.GeomType.LINESTRING -> "LineString"
            Tile.GeomType.POINT -> "Point"
            Tile.GeomType.POLYGON -> "Polygon"
            Tile.GeomType.UNKNOWN -> "Unknown"
            is Tile.GeomType.UNRECOGNIZED -> "Unrecognized"
            null -> ""
        }
        return props
    }
}