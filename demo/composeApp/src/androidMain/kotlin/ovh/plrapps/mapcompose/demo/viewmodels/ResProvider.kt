package ovh.plrapps.mapcompose.demo.viewmodels

import android.content.Context
import kotlinx.io.asSource
import ovh.plrapps.mapcompose.core.TileStreamProvider

fun makeResTileStreamProvider(appContext: Context): TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        runCatching {
            appContext.assets?.open("files/tiles/mont_blanc_layered/$zoomLvl/$row/$col.jpg")
        }.getOrNull()?.asSource()
    }
}
