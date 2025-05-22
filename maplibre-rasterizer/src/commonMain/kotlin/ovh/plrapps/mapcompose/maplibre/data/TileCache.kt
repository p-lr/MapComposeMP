package ovh.plrapps.mapcompose.maplibre.data

interface TileCache {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, data: ByteArray)
    suspend fun clear()
}