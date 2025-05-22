package ovh.plrapps.mapcompose.maplibre.cache

import ovh.plrapps.mapcompose.maplibre.data.TileCache

expect class FileTileCache(cacheDir: String) : TileCache {
    override suspend fun get(key: String): ByteArray?
    override suspend fun put(key: String, data: ByteArray)
    override suspend fun clear()
} 