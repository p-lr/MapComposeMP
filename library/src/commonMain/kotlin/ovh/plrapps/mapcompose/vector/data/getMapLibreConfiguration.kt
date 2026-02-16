package ovh.plrapps.mapcompose.vector.data

import io.ktor.http.Url
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import ovh.plrapps.mapcompose.vector.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.vector.spec.style.sprites
import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson

suspend fun getMapLibreConfiguration(styleUrl: Url, pixelRatio: Int = 1, loadResource: suspend (String) -> RawSource?): Result<MapLibreConfiguration> {
    try {
        val rawStyle = loadResource(styleUrl.toString())?.buffered()?.readString() ?: throw Exception("Style not found")
        return getMapLibreConfiguration(rawStyle, pixelRatio, loadResource)

//        val style = json.decodeFromString(MapLibreStyle.serializer(), rawStyle)
//        val tileSources = mutableMapOf<String, MapLibreTileSource>()
//
//        style.sources?.toList()?.forEach { (name, source) ->
//            val sourceUrl = source.url
//            if (sourceUrl !== null) {
//                val tileJson = getTileJson(Url(sourceUrl), loadResource).getOrElse { e -> return Result.failure(e) }
//                tileSources[name] = MapLibreTileSource(tileJson)
//            }
//        }
//
//        val spriteManager = style.sprites.firstOrNull()?.url?.let { sprite ->
//            SpriteManager.load(spriteUrl = sprite, pixelRatio = pixelRatio, loadResource = loadResource).getOrElse { e -> return Result.failure(e) }
//        }
//
//        return Result.success(MapLibreConfiguration(
//            style = style,
//            tileSources = tileSources,
//            spriteManager = spriteManager
//        ))

    } catch (e: Exception) {
        return Result.failure(e)
    }
}

suspend fun getMapLibreConfiguration(style: String, pixelRatio: Int = 1, loadResource: suspend (String) -> RawSource?): Result<MapLibreConfiguration> {
    try {
        val style = json.decodeFromString(MapLibreStyle.serializer(), style)
        val tileSources = mutableMapOf<String, MapLibreTileSource>()

        style.sources?.toList()?.forEach { (name, source) ->
            val sourceUrl = source.url
            val tiles = source.tiles
            if (sourceUrl !== null) {
                val tileJson = getTileJson(Url(sourceUrl), loadResource).getOrElse { e -> return Result.failure(e) }
                tileSources[name] = MapLibreTileSource(tileJson)
            } else if(tiles != null) {
                tileSources[name] = MapLibreTileSource(
                    TileJson(
                        tilejson = "2.0.0",
                        tiles = tiles,
                        maxzoom = source.maxzoom ?: 22,
                        minzoom = source.minzoom ?: 0,
                    )
                )
            }
        }

        val spriteManager = style.sprites.firstOrNull()?.url?.let { sprite ->
            SpriteManager.load(spriteUrl = sprite, pixelRatio = pixelRatio, loadResource = loadResource).getOrElse { e -> return Result.failure(e) }
        }

        return Result.success(MapLibreConfiguration(
            style = style,
            tileSources = tileSources,
            spriteManager = spriteManager
        ))

    } catch (e: Exception) {
        return Result.failure(e)
    }
}

suspend fun getTileJson(tileJsonUrl: Url, loadResource: suspend (String) -> RawSource?): Result<TileJson> {
    return try {
        val rawTileJson = loadResource(tileJsonUrl.toString())?.buffered()?.readString() ?: throw Exception("TileJson not found")
        val tileJson = json.decodeFromString(TileJson.serializer(), rawTileJson)

        Result.success(tileJson)
    } catch (e: Exception) {
        Result.failure(e)
    }
}