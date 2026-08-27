package ovh.plrapps.mapcompose.vector.data

import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import ovh.plrapps.mapcompose.utils.IODispatcher
import ovh.plrapps.mapcompose.vector.data.geojson.GeoJsonSource
import ovh.plrapps.mapcompose.vector.data.glyphs.GlyphManager
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.sprites
import ovh.plrapps.mapcompose.vector.spec.style.utils.StyleDiagnostics
import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson

suspend fun getMapLibreConfiguration(
    style: String,
    pixelRatio: Int = 1,
    loadResource: suspend (String) -> RawSource?
): Result<MapLibreConfiguration> {
    try {
        StyleDiagnostics.drain() // discard anything left over from an earlier parse
        val style = json.decodeFromString(MapLibreStyle.serializer(), style)
        val diagnostics = StyleDiagnostics.drain()
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

        /* The glyph server is lazy: nothing is fetched until a label needs a codepoint range, so a
         * style declaring `glyphs` costs nothing until a symbol layer actually draws. */
        val glyphManager = style.glyphs
            ?.takeIf { it.isNotEmpty() }
            ?.let { GlyphManager(urlTemplate = it, loadResource = loadResource) }

        return Result.success(MapLibreConfiguration(
            style = style,
            tileSources = tileSources,
            geoJsonSources = geoJsonSources,
            spriteManager = spriteManager,
            glyphManager = glyphManager,
            diagnostics = diagnostics,
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