package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonSource
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostic

data class MapLibreConfiguration(
    val style: MapLibreStyle,
    val tileSources: Map<String, MapLibreTileSource>,
    /**
     * The style's `geojson` sources, keyed by name.
     *
     * They are held apart from [tileSources] because they have no URL template and no
     * server: a tile is cut out of the loaded document on demand.
     */
    val geoJsonSources: Map<String, GeoJsonSource> = emptyMap(),
    val spriteManager: SpriteManager?,
    /**
     * The style's SDF glyph server, or `null` when the style declares no `glyphs` URL.
     *
     * A style without one still renders labels: the symbol painter falls back to measuring and
     * drawing them with Compose's own text stack, which ignores `text-font` and approximates the
     * halo. See `SymbolLayerLayout`.
     */
    val glyphManager: GlyphManager? = null,
    val collisionDetectionEnabled: Boolean = true,
    val lang: LanguageCode? = LanguageCode.English,
    /**
     * Expression and filter problems found while parsing the style.
     *
     * Parsing is deliberately lenient — a property or filter that fails to compile is skipped
     * rather than aborting the whole style — so this is where those failures surface.
     */
    val diagnostics: List<StyleDiagnostic> = emptyList(),
    /**
     * The style's `state` defaults, flattened to the shape `["global-state", k]` reads.
     *
     * Nothing in the render path consults this: the same map is baked into every `StyleExpression`
     * at compile time, which is where `contextFor` injects it, exactly as upstream passes
     * `globalState` to `createExpression`. It is carried here so a caller can see what the style
     * declared -- and it is what a future runtime setter would have to swap and invalidate against.
     */
    val globalState: Map<String, Any?> = emptyMap(),
)
