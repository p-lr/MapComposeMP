package ovh.plrapps.mapcompose.demo.viewmodels

import android.content.Context
import kotlinx.io.asSource
import ovh.plrapps.mapcompose.core.TileStreamProvider

fun makeResTileStreamProvider(appContext: Context, folder: String): TileStreamProvider {
    return TileStreamProvider { row, col, zoomLvl ->
        runCatching {
            appContext.assets?.open("composeResources/ovh.plrapps.mapcomposemp.demo/files/tiles/$folder/$zoomLvl/$row/$col.jpg")
        }.getOrNull()?.asSource()
    }
}
