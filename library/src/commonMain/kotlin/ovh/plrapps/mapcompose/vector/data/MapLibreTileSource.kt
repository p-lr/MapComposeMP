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
 * @property pixelRatio The display's pixel ratio, which is what `{ratio}` expands from. A lambda
 * rather than a value because a source is built while the style loads, before `MapUI` has composed
 * and the map knows its density, whereas a tile URL is only ever built by a fetch that happens
 * after -- see `VectorLayer.makeTileStreamProvider`.
 */
class MapLibreTileSource(
    val tileJson: TileJson,
    val type: SourceType = SourceType.VECTOR,
    val demUnpack: DemUnpack? = null,
    private val pixelRatio: () -> Float = { 1f },
) {

    companion object {
        const val SEGMENT_ZOOM = "{z}"
        const val SEGMENT_X = "{x}"
        const val SEGMENT_Y = "{y}"
        const val SEGMENT_PREFIX = "{prefix}"
        const val SEGMENT_RATIO = "{ratio}"
        const val SEGMENT_QUADKEY = "{quadkey}"
        const val SEGMENT_BBOX = "{bbox-epsg-3857}"

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
    fun resolve(z: Int, x: Int, y: Int): TileRef? =
        resolveOverscaled(z = z, x = x, y = y, minZoom = minZoom, maxZoom = maxZoom)

    fun getTileUrl(ref: TileRef): String = getTileUrl(z = ref.z, x = ref.x, y = ref.y)

    /**
     * The URL of the tile at [z]/[x]/[y], with every token upstream's `CanonicalTileID.url`
     * (`src/tile/tile_id.ts`) substitutes:
     *
     * ```
     * return urls[(this.x + this.y) % urls.length]
     *     .replace(/{prefix}/g, (this.x % 16).toString(16) + (this.y % 16).toString(16))
     *     .replace(/{z}/g, String(this.z))
     *     .replace(/{x}/g, String(this.x))
     *     .replace(/{y}/g, String(scheme === 'tms' ? (Math.pow(2, this.z) - this.y - 1) : this.y))
     *     .replace(/{ratio}/g, pixelRatio > 1 ? '@2x' : '')
     *     .replace(/{quadkey}/g, quadkey)
     *     .replace(/{bbox-epsg-3857}/g, bbox);
     * ```
     *
     * `{y}` is the only token the `scheme` reaches: `{quadkey}` and `{bbox-epsg-3857}` are built
     * from the plain xyz row, and the bounding box does its own, unconditional flip.
     *
     * The template is chosen by `(x + y) % size`, as upstream does, and deliberately not at random:
     * a random shard means the same tile is requested from a different host on every retry, so
     * nothing downstream of the fetch -- an HTTP cache included -- can recognise it.
     */
    fun getTileUrl(z: Int, x: Int, y: Int): String {
        val template = tileTemplates[(x + y).mod(tileTemplates.size)]

        /* TileJSON's `scheme` says which way the y axis runs. "xyz" -- the default, and what
         * MapCompose's tile pyramid uses -- counts rows from the top; "tms" counts them from the
         * bottom, so a tms source needs the row mirrored or every tile lands in the wrong place. */
        val row = if (tileJson.scheme.equals(SCHEME_TMS, ignoreCase = true)) {
            (1 shl z) - 1 - y
        } else {
            y
        }

        return template
            .replace(SEGMENT_PREFIX, tilePrefix(x = x, y = y))
            .replace(SEGMENT_ZOOM, z.toString())
            .replace(SEGMENT_X, x.toString())
            .replace(SEGMENT_Y, row.toString())
            .replace(SEGMENT_RATIO, if (pixelRatio() > 1f) "@2x" else "")
            .replace(SEGMENT_QUADKEY, tileQuadkey(z = z, x = x, y = y))
            .replace(SEGMENT_BBOX, tileBBoxEpsg3857(z = z, x = x, y = y))
    }
}

/**
 * The tile of a source with zoom range [minZoom]..[maxZoom] that covers the map tile at [z]/[x]/[y].
 *
 * Shared by every source type, because upstream's rule is the same for all of them: below a source's
 * `minzoom` there is nothing to draw (`covering_tiles.ts` returns no tiles at all), and above its
 * `maxzoom` the requested zoom is clamped to `maxzoom` while the *display* zoom is remembered
 * separately -- MapLibre's `OverscaledTileID`, where `canonical.z` is the clamped one and
 * `overscaledZ` the requested one.
 */
internal fun resolveOverscaled(z: Int, x: Int, y: Int, minZoom: Int, maxZoom: Int): TileRef? {
    if (z < minZoom) return null
    if (z <= maxZoom) return TileRef.whole(z = z, x = x, y = y)

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
) {
    /** Whether this is the requested tile itself rather than a sub-square of an ancestor. */
    val isWholeTile: Boolean get() = span <= 1

    companion object {
        /** The identity ref: the tile at [z]/[x]/[y] itself, used wherever nothing is overzoomed. */
        fun whole(z: Int, x: Int, y: Int): TileRef =
            TileRef(z = z, x = x, y = y, subX = 0, subY = 0, span = 1)
    }
}
