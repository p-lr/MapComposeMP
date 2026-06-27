package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson

class MapLibreTileSource(val tileJson: TileJson) {

    companion object {
        const val SEGMENT_ZOOM = "{z}"
        const val SEGMENT_X = "{x}"
        const val SEGMENT_Y = "{y}"
    }

    private val tileTemplates: List<String> = tileJson.tiles

    fun getTileUrl(z: Int, x: Int, y: Int): String {
        val template = tileTemplates.random()

        return template
            .replace(SEGMENT_ZOOM, z.toString())
            .replace(SEGMENT_X, x.toString())
            .replace(SEGMENT_Y, y.toString())
    }
}