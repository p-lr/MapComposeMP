package ovh.plrapps.mapcompose.maplibre.data

import ovh.plrapps.mapcompose.maplibre.spec.style.MapLibreStyle
import ovh.plrapps.mapcompose.maplibre.spec.tilejson.TileJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import ovh.plrapps.mapcompose.maplibre.spec.style.sprites

suspend fun getMapLibreConfiguration(styleUrl: Url, pixelRatio: Int = 1): Result<MapLibreConfiguration> {
    try {
        val rawStyle = httpClient.get(styleUrl).bodyAsText()
        val style = json.decodeFromString(MapLibreStyle.serializer(), rawStyle)
        val tileSources = mutableMapOf<String, MapLibreTileSource>()

        style.sources?.toList()?.forEach { (name, source) ->
            val sourceUrl = source.url
            if (sourceUrl !== null) {
                val tileJson = getTileJson(Url(sourceUrl)).getOrElse { e -> return Result.failure(e) }
                tileSources[name] = MapLibreTileSource(tileJson)
            }
        }

        val spriteManager = style.sprites.firstOrNull()?.url?.let { sprite ->
            SpriteManager.load(spriteUrl = sprite, pixelRatio = pixelRatio).getOrElse { e -> return Result.failure(e) }
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

suspend fun getMapLibreConfiguration(style: String, pixelRatio: Int = 1): Result<MapLibreConfiguration> {
    try {
        val style = json.decodeFromString(MapLibreStyle.serializer(), style)
        val tileSources = mutableMapOf<String, MapLibreTileSource>()

        style.sources?.toList()?.forEach { (name, source) ->
            val sourceUrl = source.url
            val tiles = source.tiles
            if (sourceUrl !== null) {
                val tileJson = getTileJson(Url(sourceUrl)).getOrElse { e -> return Result.failure(e) }
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
            SpriteManager.load(spriteUrl = sprite, pixelRatio = pixelRatio).getOrElse { e -> return Result.failure(e) }
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

suspend fun getTileJson(tileJsonUrl: Url): Result<TileJson> {
    return try {
        val rawTileJson = httpClient.get(tileJsonUrl).bodyAsText()
        val tileJson = json.decodeFromString(TileJson.serializer(), rawTileJson)

        Result.success(tileJson)
    } catch (e: Exception) {
        Result.failure(e)
    }
}