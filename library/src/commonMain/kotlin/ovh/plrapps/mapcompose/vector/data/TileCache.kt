package ovh.plrapps.mapcompose.vector.data

interface TileCache {
    suspend fun get(sourceName: String, key: String): ByteArray?
    suspend fun put(sourceName: String, key: String, data: ByteArray)
    suspend fun clear()
}