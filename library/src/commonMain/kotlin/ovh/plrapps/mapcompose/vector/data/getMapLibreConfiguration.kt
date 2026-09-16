package ovh.plrapps.mapcompose.vector.data

import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonSource
import ovh.plrapps.mapcompose.vector.data.glyphs.FontFaceManager
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.data.glyphs.LocalGlyphSource
import ovh.plrapps.mapcompose.vector.spec.style.Source
import ovh.plrapps.mapcompose.vector.spec.style.fontFaces
import ovh.plrapps.mapcompose.vector.spec.style.sprites
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson

suspend fun getMapLibreConfiguration(
    style: String,
    pixelRatio: Int = 1,
    /**
     * Draws a grapheme with a font file the style declared in its root `font-faces`.
     *
     * Null leaves such a style to its `glyphs` server, which is what a caller with no text stack to
     * draw with -- a test, or a decode outside a composition -- has to do. `VectorLayer` passes a
     * rasterizer over the map's own `TextMeasurer`. It comes before [loadResource] so that the
     * loader stays the trailing lambda every caller writes it as.
     */
    localGlyphs: LocalGlyphSource? = null,
    loadResource: suspend (String) -> RawSource?,
): Result<MapLibreConfiguration> {
    try {
        StyleDiagnostics.drain() // discard anything left over from an earlier parse
        val style = decodeStyle(style)
        /* Read before the drain, so that a malformed font face declaration is reported with
         * everything else the parse found rather than being dropped on the floor. */
        val fontFaceManager = style.fontFaces
            .takeIf { it.isNotEmpty() }
            ?.let { FontFaceManager(declarations = it, loadResource = loadResource) }
            ?.takeIf { it.hasFontFaces }
        val diagnostics = StyleDiagnostics.drain()
        val globalState = style.globalStateDefaults()
        val tileSources = mutableMapOf<String, MapLibreTileSource>()

        val geoJsonSources = mutableMapOf<String, GeoJsonSource>()

        style.sources?.toList()?.forEach { (name, source) ->
            val sourceUrl = source.url
            val tiles = source.tiles
            val type = SourceType.fromSpec(source.type)
            if (type == SourceType.GEOJSON) {
                /* A geojson source has no tile URL: the whole document is loaded once and cut into
                 * tiles on demand, so it never reaches the tile fetcher at all. */
                GeoJsonSource.load(source, loadResource)?.let { geoJsonSources[name] = it }
                return@forEach
            }
            val tileJson = if (sourceUrl !== null) {
                getTileJson(sourceUrl, loadResource)
                    .getOrElse { e -> return Result.failure(e) }
                    .overriddenBy(source)
            } else if (tiles != null) {
                TileJson(
                    tilejson = "2.0.0",
                    tiles = tiles,
                    maxzoom = source.maxzoom ?: 22,
                    minzoom = source.minzoom ?: 0,
                    scheme = source.scheme ?: "xyz",
                    tileSize = source.tileSize,
                    encoding = source.encoding,
                )
            } else {
                return@forEach
            }
            /* Only a raster-dem source's channels mean elevation; every other type leaves this null
             * so nothing else can be mistaken for a DEM. The encoding is read off the merged
             * TileJson rather than off the source, because a referenced document may be the one
             * declaring it. */
            val demUnpack = if (type == SourceType.RASTER_DEM) {
                DemUnpack.of(source, encoding = tileJson.encoding)
            } else null
            tileSources[name] = MapLibreTileSource(tileJson, type, demUnpack)
        }

        /* Every declared sheet, not just the first: a list-form `sprite` namespaces each sheet's
         * entries by its id, and dropping all but one sheet loses every icon the others define. */
        val spriteSheets = style.sprites.mapNotNull { source ->
            val url = source.url ?: return@mapNotNull null
            SpriteManager.loadSheet(
                spriteUrl = url,
                pixelRatio = pixelRatio,
                id = source.id.orEmpty(),
                loadResource = loadResource,
            ).getOrElse { e -> return Result.failure(e) }
        }
        val spriteManager = spriteSheets.takeIf { it.isNotEmpty() }?.let { SpriteManager(it) }

        /* Both halves are lazy: nothing is fetched until a label needs a codepoint range or a
         * declared font file, so a style declaring `glyphs` or `font-faces` costs nothing until a
         * symbol layer actually draws. A style with font files and no server still gets a manager --
         * it has fonts of its own to draw with. */
        val glyphUrl = style.glyphs?.takeIf { it.isNotEmpty() }
        val glyphManager = if (glyphUrl == null && fontFaceManager == null) {
            null
        } else {
            GlyphManager(
                urlTemplate = glyphUrl,
                loadResource = loadResource,
                fontFaces = fontFaceManager,
                localGlyphs = localGlyphs,
            )
        }

        return Result.success(MapLibreConfiguration(
            style = style,
            tileSources = tileSources,
            geoJsonSources = geoJsonSources,
            spriteManager = spriteManager,
            glyphManager = glyphManager,
            diagnostics = diagnostics,
            globalState = globalState,
        ))

    } catch (e: Exception) {
        return Result.failure(e)
    }
}

/**
 * The TileJSON a source actually uses: the document it references, with every option the style wrote
 * on the source itself applied over it.
 *
 * Upstream's `src/source/load_tilejson.ts`, whose own comment is "explicit source options take
 * precedence over TileJSON":
 *
 * ```
 * pick(extend(tileJSON, options),
 *      ['tiles', 'minzoom', 'maxzoom', 'attribution', 'bounds', 'scheme', 'tileSize', 'encoding'])
 * ```
 *
 * `options` there is the raw style-source object, so a key the style did not write is simply absent
 * and leaves the served value standing -- which is exactly what `?:` does over [Source]'s nullable
 * fields. Without this a source overriding a referenced TileJSON to `"scheme": "tms"` still
 * addressed rows the document's way and every tile landed mirrored.
 *
 * `redFactor` and the other three `custom` DEM factors are deliberately absent: they are not in
 * upstream's pick list, so only `encoding` itself can arrive from a TileJSON.
 */
private fun TileJson.overriddenBy(source: Source): TileJson = copy(
    tiles = source.tiles ?: tiles,
    minzoom = source.minzoom ?: minzoom,
    maxzoom = source.maxzoom ?: maxzoom,
    scheme = source.scheme ?: scheme,
    attribution = source.attribution ?: attribution,
    bounds = source.bounds ?: bounds,
    tileSize = source.tileSize ?: tileSize,
    encoding = source.encoding ?: encoding,
)

suspend fun getTileJson(tileJsonUrl: String, loadResource: suspend (String) -> RawSource?): Result<TileJson> {
    return try {
        val rawTileJson = withContext(IODispatcher) {
            loadResource(tileJsonUrl)?.buffered()?.readString() ?: throw Exception("TileJson not found")
        }
        val tileJson = json.decodeFromString(TileJson.serializer(), rawTileJson)

        Result.success(tileJson)
    } catch (e: Exception) {
        Result.failure(e)
    }
}