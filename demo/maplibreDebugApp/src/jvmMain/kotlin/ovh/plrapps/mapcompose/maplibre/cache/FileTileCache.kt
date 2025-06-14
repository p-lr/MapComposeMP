package ovh.plrapps.mapcompose.maplibre.cache

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ovh.plrapps.mapcompose.maplibre.data.TileCache

actual class FileTileCache actual constructor(private val cacheDir: String) : TileCache {
    private val cacheDirectories = mutableMapOf<String, File>()

    private fun createCacheDir(sourceName: String): File {
        return File("$cacheDir/$sourceName").apply { mkdirs() }
    }


    private fun getCacheFile(sourceName: String, key: String): File {
        val cacheDirectory = cacheDirectories.getOrPut(sourceName) { createCacheDir(sourceName) }
        return File(cacheDirectory, key)
    }

    actual override suspend fun get(sourceName: String, key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = getCacheFile(sourceName, key)
        if (file.exists()) file.readBytes() else null
    }

    actual override suspend fun put(sourceName: String, key: String, data: ByteArray) = withContext(Dispatchers.IO) {
        getCacheFile(sourceName, key).writeBytes(data)
    }

    actual override suspend fun clear() = withContext(Dispatchers.IO) {
        cacheDirectories.forEach { (_, cacheDirectory) ->
            cacheDirectory.listFiles()?.forEach { it.delete() }
        }
    }
} 