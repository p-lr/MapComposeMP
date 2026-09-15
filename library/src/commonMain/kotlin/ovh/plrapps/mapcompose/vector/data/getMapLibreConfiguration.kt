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
            /* Only a raster-dem source's channels mean elevation; every other type leaves this null
             * so nothing else can be mistaken for a DEM. */
            val demUnpack = if (type == SourceType.RASTER_DEM) DemUnpack.of(source) else null
            if (sourceUrl !== null) {
                val tileJson = getTileJson(sourceUrl, loadResource).getOrElse { e -> return Result.failure(e) }
                tileSources[name] = MapLibreTileSource(tileJson, type, demUnpack)
            } else if(tiles != null) {
                tileSources[name] = MapLibreTileSource(
                    TileJson(
                        tilejson = "2.0.0",
                        tiles = tiles,
                        maxzoom = source.maxzoom ?: 22,
                        minzoom = source.minzoom ?: 0,
                        scheme = source.scheme ?: "xyz",
                    ),
                    type,
                    demUnpack,
                )
            }
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