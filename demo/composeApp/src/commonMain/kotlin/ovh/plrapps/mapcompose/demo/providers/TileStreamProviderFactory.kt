package ovh.plrapps.mapcompose.demo.providers

import mapcompose_mp.demo.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import ovh.plrapps.mapcompose.core.TileStreamProvider

@OptIn(ExperimentalResourceApi::class)
fun makeTileStreamProvider() =
    TileStreamProvider { row, col, zoomLvl ->
        try {
            Res.readBytes("files/tiles/mont_blanc/$zoomLvl/$row/$col.jpg")
        } catch (e: Exception) {
            null
        }
    }
