package ovh.plrapps.mapcompose.maplibre.cache

import ovh.plrapps.mapcompose.maplibre.data.TileCache

/**
 * TODO OPFS implementation for browsers (js/wasm)
 */
actual class FileTileCache actual constructor(cacheDir: String) :
    TileCache {
    actual override suspend fun get(sourceName: String, key: String): ByteArray? {
        TODO("Not yet implemented")
    }

    actual override suspend fun put(sourceName: String, key: String, data: ByteArray) {
        TODO("Not yet implemented")
    }

    actual override suspend fun clear() {
        TODO("Not yet implemented")
    }
}