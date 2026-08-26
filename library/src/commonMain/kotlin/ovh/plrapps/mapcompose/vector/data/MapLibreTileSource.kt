package ovh.plrapps.mapcompose.vector.data

import ovh.plrapps.mapcompose.vector.spec.tilejson.TileJson

/**
 * One tile source of a style: what [type] of data it serves, and which URL serves a given tile.
 *
 * @property type What the bytes at [getTileUrl] are. The rasterizer decodes a [SourceType.VECTOR]
 * source's tiles as MVT protobuf, and a [SourceType.RASTER] or [SourceType.RASTER_DEM] source's as
 * an image; nothing else is fetched at all.
 * @property demUnpack How to read elevation out of that image, set for a [SourceType.RASTER_DEM]
 * source and `null` for every other type.
 */
class MapLibreTileSource(
    val tileJson: TileJson,
    val type: SourceType = SourceType.VECTOR,
    val demUnpack: DemUnpack? = null,
) {

    companion object {
        const val SEGMENT_ZOOM = "{z}"
        const val SEGMENT_X = "{x}"
        const val SEGMENT_Y = "{y}"

        private const val SCHEME_TMS = "tms"
    }

    private val tileTemplates: List<String> = tileJson.tiles

    val minZoom: Int get() = tileJson.minzoom
    val maxZoom: Int get() = tileJson.maxzoom

    /**
     * Which tile of this source covers the map tile at [z]/[x]/[y], and which part of it.
     *
     * Below the source's `minzoom` there is nothing to draw and this returns `null`, as upstream --
     * a source simply does not exist at a zoom it does not publish. Above its `maxzoom` the source
     * is *overzoomed*: MapLibre keeps drawing the deepest ancestor tile it has, magnified, rather
     * than dropping the layer, which is what raster basemaps that stop at z17-19 rely on. The
     * ancestor's coordinates are the requested ones shifted down by the zoom difference, and
     * [TileRef.subX] / [TileRef.subY] / [TileRef.span] say which of its `span x span` sub-squares
     * the requested tile is.
     */
    fun resolve(z: Int, x: Int, y: Int): TileRef? {
        if (z < minZoom) return null
        if (z <= maxZoom) return TileRef(z = z, x = x, y = y, subX = 0, subY = 0, span = 1)

        val dz = z - maxZoom
        val span = 1 shl dz
        return TileRef(
            z = maxZoom,
            x = x shr dz,
            y = y shr dz,
            subX = x and (span - 1),
            subY = y and (span - 1),
            span = span,
        )
    }

    fun getTileUrl(ref: TileRef): String = getTileUrl(z = ref.z, x = ref.x, y = ref.y)

    fun getTileUrl(z: Int, x: Int, y: Int): String {
        val template = tileTemplates.random()

        /* TileJSON's `scheme` says which way the y axis runs. "xyz" -- the default, and what
         * MapCompose's tile pyramid uses -- counts rows from the top; "tms" counts them from the
         * bottom, so a tms source needs the row mirrored or every tile lands in the wrong place. */
        val row = if (tileJson.scheme.equals(SCHEME_TMS, ignoreCase = true)) {
            (1 shl z) - 1 - y
        } else {
            y
        }

        return template
            .replace(SEGMENT_ZOOM, z.toString())
            .replace(SEGMENT_X, x.toString())
            .replace(SEGMENT_Y, row.toString())
    }
}

/**
 * A tile of a source, and which part of it a map tile needs.
 *
 * [span] is 1 for the common case, where the source has the requested zoom and the whole tile is
 * used. When the source is overzoomed it is `2^(requested z - source maxzoom)`, and the map tile
 * covers only the `(subX, subY)` sub-square of a `span x span` grid over this tile.
 */
data class TileRef(
    val z: Int,
    val x: Int,
    val y: Int,
    val subX: Int,
    val subY: Int,
    val span: Int,
)
