package ovh.plrapps.mapcompose.demo.providers

import kotlinx.io.RawSource
import ovh.plrapps.mapcompose.core.TileStreamProvider

fun makeTileStreamProvider() =
    TileStreamProvider { row, col, zoomLvl ->
        try {
            assetFileProvider.get("files/tiles/mont_blanc/$zoomLvl/$row/$col.jpg")
        } catch (e: Exception) {
            null
        }
    }

fun interface AssetFileProvider {
    fun get(path: String): RawSource?
}

expect val assetFileProvider: AssetFileProvider
