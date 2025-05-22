package ovh.plrapps.mapcompose.maplibre.cache

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ovh.plrapps.mapcompose.maplibre.data.TileCache

actual class FileTileCache actual constructor(private val cacheDir: String) : TileCache {
    private val cacheDirectory = File(cacheDir).apply { mkdirs() }

    private fun getCacheFile(key: String): File {
        return File(cacheDirectory, key)
    }

    actual override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = getCacheFile(key)
        if (file.exists()) file.readBytes() else null
    }

    actual override suspend fun put(key: String, data: ByteArray) = withContext(Dispatchers.IO) {
        getCacheFile(key).writeBytes(data)
    }

    actual override suspend fun clear() = withContext(Dispatchers.IO) {
        cacheDirectory.listFiles()?.forEach { it.delete() }
        Unit
    }
} 