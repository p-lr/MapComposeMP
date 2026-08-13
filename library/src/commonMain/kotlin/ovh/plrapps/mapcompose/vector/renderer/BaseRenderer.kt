package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import ovh.plrapps.mapcompose.vector.spec.style.expression.normalizeNumbers

abstract class BaseRenderer(
    private val configuration: MapLibreConfiguration,
) {

    /**
     * Evaluates the layer's filter against a feature.
     *
     * The zoom passed to a filter is the tile's *integer* zoom, not the fractional map zoom. That
     * matches MapLibre, where buckets evaluate `featureFilter.filter(new
     * EvaluationParameters(this.zoom), …)` with the tile's canonical zoom while paint properties
     * are evaluated at the fractional zoom.
     */
    fun shouldRenderFeature(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
        styleLayer: Layer,
        zoom: Double,
        evalFeature: EvalFeature? = null,
        canonical: CanonicalTileId? = null,
    ): Boolean {
        val filter = styleLayer.filter?.filter ?: return true
        val target = evalFeature ?: buildEvalFeature(feature, tileLayer, filter.needGeometry)
        return filter.filter(
            globals = GlobalProperties(zoom = zoom.toInt().toDouble()),
            feature = target,
            canonical = canonical,
        )
    }

    private val MAX_ZOOM = 30.0

    fun isZoomInRange(styleLayer: Layer, zoom: Double): Boolean {
        val minZoom = styleLayer.minzoom ?: 0.0
        val maxZoom = styleLayer.maxzoom ?: MAX_ZOOM
        return zoom in minZoom..<maxZoom
    }

    /**
     * Builds the expression-evaluation view of an MVT feature.
     *
     * Geometry decoding is deferred behind a lambda and only requested when [needGeometry] is set,
     * i.e. when some `within` or `distance` expression actually reads it — MapLibre gates it the
     * same way with `FeatureFilter.needGeometry`.
     */
    fun buildEvalFeature(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
        needGeometry: Boolean = false,
    ): EvalFeature = EvalFeature(
        type = geometryTypeOf(feature),
        id = feature.id?.toDouble(),
        properties = extractFeatureProperties(feature, tileLayer),
        geometryProvider = if (needGeometry) {
            { decodeRawGeometry(feature.geometry) }
        } else {
            null
        },
    )

    fun geometryTypeOf(feature: Tile.Feature): String = when (feature.type) {
        Tile.GeomType.LINESTRING -> "LineString"
        Tile.GeomType.POINT -> "Point"
        Tile.GeomType.POLYGON -> "Polygon"
        Tile.GeomType.UNKNOWN -> "Unknown"
        is Tile.GeomType.UNRECOGNIZED -> "Unknown"
        null -> "Unknown"
    }

    /**
     * Feature properties, with every numeric value normalized to [Double].
     *
     * The expression engine models numbers the way JavaScript does, so MVT's Int/Float/Long/UInt
     * variants must not leak in — see the note on `normalizeNumbers`. This is what makes
     * `["==", ["get", "n"], 1]` match a property the tile encoded as a float.
     */
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
                val raw = value.stringValue ?: value.floatValue ?: value.doubleValue ?: value.intValue
                    ?: value.uintValue ?: value.sintValue ?: value.boolValue
                props[key] = normalizeNumbers(raw)
            }
        }
        return props
    }

    /**
     * Decodes MVT geometry commands into rings of tile-local coordinates.
     *
     * Unlike [GeometryDecoders], which scales to canvas pixels, this keeps the raw 0..extent tile
     * coordinate space that `within` and `distance` are defined in.
     */
    private fun decodeRawGeometry(geometry: List<Int>): List<List<Point2D>> {
        val rings = mutableListOf<List<Point2D>>()
        var current = mutableListOf<Point2D>()
        var x = 0
        var y = 0
        var i = 0

        while (i < geometry.size) {
            val commandInteger = geometry[i++]
            val commandId = commandInteger and 0x7
            val count = commandInteger shr 3

            when (commandId) {
                1 -> { // MoveTo
                    repeat(count) {
                        if (i + 1 >= geometry.size) return@repeat
                        if (current.isNotEmpty()) {
                            rings.add(current)
                            current = mutableListOf()
                        }
                        x += GeometryDecoders.decodeZigZag(geometry[i++])
                        y += GeometryDecoders.decodeZigZag(geometry[i++])
                        current.add(Point2D(x.toDouble(), y.toDouble()))
                    }
                }

                2 -> { // LineTo
                    repeat(count) {
                        if (i + 1 >= geometry.size) return@repeat
                        x += GeometryDecoders.decodeZigZag(geometry[i++])
                        y += GeometryDecoders.decodeZigZag(geometry[i++])
                        current.add(Point2D(x.toDouble(), y.toDouble()))
                    }
                }

                7 -> { // ClosePath
                    if (current.isNotEmpty()) current.add(current.first())
                }
            }
        }

        if (current.isNotEmpty()) rings.add(current)
        return rings
    }
}
