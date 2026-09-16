package ovh.plrapps.mapcompose.vector.renderer

import ovh.plrapps.mapcompose.vector.data.MapLibreConfiguration
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonTiler
import ovh.plrapps.mapcompose.vector.spec.Tile
import ovh.plrapps.mapcompose.vector.spec.style.Layer
import ovh.plrapps.mapcompose.vector.spec.style.StyleSpecDefaults
import ovh.plrapps.mapcompose.vector.spec.style.expression.CanonicalTileId
import ovh.plrapps.mapcompose.vector.spec.style.expression.EvalFeature
import ovh.plrapps.mapcompose.vector.spec.style.expression.GlobalProperties
import ovh.plrapps.mapcompose.vector.spec.style.expression.Point2D
import ovh.plrapps.mapcompose.vector.spec.style.expression.geometry.EXTENT
import ovh.plrapps.mapcompose.vector.spec.style.expression.normalizeNumbers
import ovh.plrapps.mapcompose.vector.spec.style.props.processAsString
import kotlin.math.round

abstract class BaseRenderer(
    protected val configuration: MapLibreConfiguration,
) {

    /** The MVT default when a layer declares no `extent`, per the vector tile spec. */
    private val DEFAULT_MVT_EXTENT = 4096

    /* `src/data/load_geometry.ts`: a scaled coordinate has to stay inside a signed 15-bit range. */
    private val GEOMETRY_MAX = 16383.0
    private val GEOMETRY_MIN = -16384.0

    /**
     * Evaluates the layer's filter against a feature.
     *
     * The zoom passed to a filter is the tile's *integer* zoom, which is what MapLibre does too:
     * its buckets evaluate `featureFilter.filter(new EvaluationParameters(this.zoom), …)` at the
     * tile's canonical zoom.
     *
     * **Divergence.** Upstream then evaluates *paint* properties at the fractional map zoom, per
     * frame, from uniforms. Here a tile is rasterized once and cached by `(row, col, z)`, so a
     * painter is handed the same integer zoom as the filter -- `VectorRasterizer.getTile` is
     * called from a `TileStreamProvider`, whose only zoom is the tile's. Evaluating paint at the
     * fractional zoom would mean re-rasterizing the whole viewport as the map zooms, and doing it
     * for *some* tiles would seam wherever a road crosses a tile edge, because two neighbours
     * rendered at different zooms disagree about every width. See `vector/README.md`.
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
        val target = evalFeature ?: buildEvalFeature(feature, tileLayer, canonical)
        /* The id the feature was built with wins, so a caller that hands over an [EvalFeature]
         * cannot silently drop the tile it came from -- which is exactly how `within` came to
         * reject every feature of a geographically filtered layer.
         *
         * [EvalFeature.filterFeature] is upstream's `toEvaluationFeature` view and is non-null only
         * for a source that promotes its ids: a filter reads the raw protobuf id there, where paint
         * and layout read the promoted one. See its KDoc. */
        return filter.filter(
            globals = GlobalProperties(zoom = zoom.toInt().toDouble()),
            feature = target.filterFeature ?: target,
            canonical = target.canonical ?: canonical,
        )
    }

    /** The style spec's maximum zoom, and so what an absent `maxzoom` means. */
    private val MAX_ZOOM = 24.0

    /**
     * Whether the layer's `visibility` layout property lets it draw.
     *
     * MapLibre treats `"none"` as "this layer does not exist for rendering purposes"
     * (`StyleLayer.isHidden`), so it is checked alongside the zoom range rather than left to each
     * painter.
     *
     * The property is an expression, and takes neither a zoom nor a feature: its spec's
     * `expression.parameters` is `["global-state"]` alone and its `property-type` is
     * `data-constant`, so upstream's `VisibilityExpressionClass.evaluate` likewise evaluates it
     * against an empty `GlobalProperties`. A property that failed to compile evaluates to `null`
     * and so reads as `"visible"`, which is upstream's fallback for a `visibility` it could not
     * parse; the errors are in `MapLibreConfiguration.diagnostics`.
     */
    fun isLayerVisible(styleLayer: Layer): Boolean =
        (styleLayer.layout.visibility.processAsString() ?: StyleSpecDefaults.VISIBILITY) !=
                StyleSpecDefaults.VISIBILITY_NONE

    /**
     * The tile layer a style layer draws from.
     *
     * A `geojson` source has no `source-layer` -- the spec forbids one, because the document is a
     * single layer -- so a layer that names none takes whatever the tile holds. Every other source
     * type is matched by name, as before.
     */
    fun tileLayerFor(tile: Tile, styleLayer: Layer): Tile.Layer? {
        val name = styleLayer.sourceLayer ?: return tile.layers.firstOrNull()
        return tile.layers.find { it.name == name }
    }

    fun isZoomInRange(styleLayer: Layer, zoom: Double): Boolean {
        val minZoom = styleLayer.minzoom ?: 0.0
        val maxZoom = styleLayer.maxzoom ?: MAX_ZOOM
        return zoom in minZoom..<maxZoom
    }

    /**
     * Builds the expression-evaluation view of an MVT feature.
     *
     * [canonical] is the tile the feature was decoded from -- for an overzoomed source that is the
     * *ancestor* actually fetched, which is upstream's `OverscaledTileID.canonical`. It is what
     * `within` and `distance` project tile-local geometry back to lng/lat with; without it both
     * answer `false` / `NaN` and the property falls back to its spec default.
     *
     * The geometry provider is attached unconditionally. `EvalFeature.geometry` is `by lazy`, so
     * the decode still happens only when an expression actually reads it -- upstream's
     * `FeatureFilter.needGeometry` gate, moved from build time to read time. Gating the *provider*
     * on the filter, as this used to, meant a `within` in a paint or layout property saw no
     * geometry at all, and worse: `TileRenderer.localPropCache` is keyed across style layers, so
     * the first layer to touch a feature decided whether every later layer's filter could see its
     * geometry.
     *
     * [promoteIdProperty] is the source's `promoteId` resolved for this tile layer -- see
     * [promoteIdPropertyFor], and `vector/README.md` for the whole of it. When it is set, *two*
     * features are built, as upstream builds two: the one returned carries the promoted id and is
     * what paint and layout evaluate against, and [EvalFeature.filterFeature] carries the raw
     * protobuf id and is what a layer filter evaluates against, which is upstream's
     * `toEvaluationFeature`. When it is not set -- nearly every source -- the raw feature is
     * returned directly and nothing extra is allocated.
     */
    fun buildEvalFeature(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
        canonical: CanonicalTileId? = null,
        promoteIdProperty: String? = null,
    ): EvalFeature {
        val extent = tileLayer.extent ?: DEFAULT_MVT_EXTENT
        val properties = extractFeatureProperties(feature, tileLayer)
        /* A `geojson` source's synthetic tile smuggles a non-integral id through the tag table,
         * because the MVT wire format has no room for one -- see [GeoJsonTiler.SYNTHETIC_ID_KEY].
         * Removing it here is what keeps it out of `["get"]` and `["properties"]`. */
        val syntheticId = properties.remove(GeoJsonTiler.SYNTHETIC_ID_KEY)
        val raw = EvalFeature(
            type = geometryTypeOf(feature),
            id = syntheticId ?: feature.id?.toDouble(),
            properties = properties,
            canonical = canonical,
            geometryProvider = { decodeRawGeometry(feature.geometry, extent) },
        )
        if (promoteIdProperty == null) return raw
        /* Upstream's `FeatureIndex.getId`: the promoted value *replaces* the id, it does not fall
         * back to it, so a property the feature does not carry leaves `["id"]` null exactly as
         * upstream leaves it `undefined`. The one coercion is the boolean one; every other type is
         * already an engine value, `extractFeatureProperties` having normalized it. Upstream's
         * `cluster_id` arm has no analogue -- geojson clustering is not supported here. */
        val promoted = properties[promoteIdProperty].let { if (it is Boolean) (if (it) 1.0 else 0.0) else it }
        return EvalFeature(
            type = raw.type,
            id = promoted,
            properties = properties,
            canonical = canonical,
            geometryProvider = { raw.geometry },
            filterFeature = raw,
        )
    }

    /**
     * The property [sourceName]'s `promoteId` promotes on [tileLayer], or `null` for neither.
     *
     * This is upstream's `FeatureIndex.getId`'s `typeof this.promoteId === 'string' ? … : …[…]`.
     * It is a pure function of the source and the source layer, which is what makes it safe to
     * apply inside the callers' per-feature caches: both are keyed by a tile of one source plus the
     * source layer's name.
     */
    fun promoteIdPropertyFor(sourceName: String?, tileLayer: Tile.Layer): String? {
        if (sourceName == null) return null
        val promoteId = configuration.promoteIds[sourceName] ?: return null
        return promoteId.propertyFor(tileLayer.name.orEmpty())
    }

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
    fun extractFeatureProperties(
        feature: Tile.Feature,
        tileLayer: Tile.Layer,
    ): MutableMap<String, Any?> {
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
     * Unlike [GeometryDecoders], which scales to canvas pixels, this keeps a tile-local coordinate
     * space -- but the engine's, not the tile's. Ported from `src/data/load_geometry.ts`: the MVT
     * layer's own [extent] (usually 4096) is rescaled to the style spec's [EXTENT] of 8192, which is
     * the unit `within` and `distance` are written in (`getTileCoordinates` multiplies by it,
     * `Within` shifts by `canonical.x * EXTENT`). Skipping the rescale, as this used to, halved
     * every coordinate and put the comparison in the wrong quarter of the tile.
     *
     * Upstream's clamp to a signed 15-bit range is kept too: a coordinate outside it cannot be
     * expressed by the vertex buffers it eventually feeds.
     */
    private fun decodeRawGeometry(geometry: List<Int>, extent: Int): List<List<Point2D>> {
        val scale = EXTENT.toDouble() / extent
        val rings = mutableListOf<List<Point2D>>()
        var current = mutableListOf<Point2D>()
        var x = 0
        var y = 0
        var i = 0

        fun add(px: Int, py: Int) {
            current.add(
                Point2D(
                    round(px * scale).coerceIn(GEOMETRY_MIN, GEOMETRY_MAX),
                    round(py * scale).coerceIn(GEOMETRY_MIN, GEOMETRY_MAX),
                )
            )
        }

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
                        add(x, y)
                    }
                }

                2 -> { // LineTo
                    repeat(count) {
                        if (i + 1 >= geometry.size) return@repeat
                        x += GeometryDecoders.decodeZigZag(geometry[i++])
                        y += GeometryDecoders.decodeZigZag(geometry[i++])
                        add(x, y)
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
