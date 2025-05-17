package ovh.plrapps.mapcompose.maplibre.data

import ovh.plrapps.mapcompose.maplibre.spec.tilejson.TileJson
import io.ktor.http.*

class MapLibreTileSource(val tileJson: TileJson) {
    companion object {
        const val SEGMENT_ZOOM = "{z}"
        const val SEGMENT_X = "{x}"
        const val SEGMENT_Y = "{y}"
    }

    val tileUrlBuilders = tileJson.tiles.map { url ->
        URLBuilder().takeFrom(url)
    }

    fun getTileUrl(z: Int, x: Int, y: Int): Url {
        val builder = tileUrlBuilders.random().clone()
        builder.pathSegments = builder.pathSegments.map { segment ->
            when {
                segment.contains(SEGMENT_ZOOM) -> segment.replace(SEGMENT_ZOOM, z.toString())
                segment.contains(SEGMENT_X) -> segment.replace(SEGMENT_X, x.toString())
                segment.contains(SEGMENT_Y) -> segment.replace(SEGMENT_Y, y.toString())
                else -> segment
            }
        }
        return builder.build()
    }
}