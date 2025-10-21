package ovh.plrapps.mapcompose.demo.providers

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcomposemp.demo.Res

/**
 * This implementation of [TileStreamProvider] eagerly reads tiles files from the resources, using
 * the Kotlin Multiplatform [Res] utility. It's enough for a demo app.
 * However, for optimal performances, a [TileStreamProvider] should return a [RawSource] (the
 * equivalent of an InputStream in java).
 */
@OptIn(ExperimentalResourceApi::class)
fun makeTileStreamProvider() =
    TileStreamProvider { row, col, zoomLvl ->
        try {
            val buffer = Buffer()
            Res.readBytes("files/tiles/mont_blanc_layered/$zoomLvl/$row/$col.jpg").let {
                buffer.write(it)
                buffer
            }
        } catch (e: Exception) {
            null
        }
    }

fun interface AssetFileProvider {
    fun get(path: String): RawSource?
}
