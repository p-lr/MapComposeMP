package ovh.plrapps.mapcompose.maplibre.data

interface TileCache {
    suspend fun get(sourceName: String, key: String): ByteArray?
    suspend fun put(sourceName: String, key: String, data: ByteArray)
    suspend fun clear()
}