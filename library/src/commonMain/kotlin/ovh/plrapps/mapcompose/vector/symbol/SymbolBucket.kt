package ovh.plrapps.mapcompose.vector.symbol

import ovh.plrapps.mapcompose.vector.data.TileRef
import kotlin.math.log2

/**
 * The nominal tile size symbol layout works in, upstream's `tileSize` in
 * `symbol_layout.ts`'s `bucket.tilePixelRatio = EXTENT / tileSize`.
 *
 * The number itself hardly matters -- what matters is that it is a **constant**. Layout used to be
 * handed the size the tile occupies on screen, which changes with every fractional zoom, so nothing
 * it produced could outlive one viewport update: a pan that fetched no tiles still re-shaped every
 * label, re-walked every line and rebuilt every collision box. Anchoring layout to a fixed size
 * makes a bucket a function of (tile, style layer, integer zoom) alone, which is what lets it be
 * cached and what lets [Placement] do the only view-dependent work.
 *
 * 512 is upstream's own, and is multiplied by the display density here because every length this
 * port measures in layout space -- a label's own width, `symbol-spacing`, `text-padding` -- is a
 * style pixel times `density.density`.
 */
internal const val LAYOUT_TILE_SIZE: Int = 512

/** [LAYOUT_TILE_SIZE] for a bucket covering [span] x [span] map tiles of an overzoomed source. */
internal fun layoutTileSize(density: Float, span: Int): Int =
    (LAYOUT_TILE_SIZE * density).toInt().coerceAtLeast(1) * span.coerceAtLeast(1)

/**
 * The map's **continuous** zoom, which is what a size expression is evaluated at.
 *
 * A tile at zoom `z` is [LAYOUT_TILE_SIZE] style pixels wide, and the world is `fullWidth * scale`
 * device pixels, so this is simply how many times [LAYOUT_TILE_SIZE] fits into the world in style
 * pixels. It needs no tile level: the layout pass is pinned to the integer one, and this is the
 * fractional distance from it that the placement and draw passes scale symbols by.
 */
internal fun fractionalZoom(fullWidth: Int, scale: Double, density: Float): Double {
    val worldStylePx = fullWidth.toDouble() * scale / density.coerceAtLeast(0.01f)
    if (worldStylePx <= 0.0) return 0.0
    return log2(worldStylePx / LAYOUT_TILE_SIZE)
}

/**
 * One style layer laid out over one canonical tile, ported from `SymbolBucket`
 * (`maplibre-gl-js/src/data/bucket/symbol_bucket.ts`).
 *
 * A bucket is built once, in the layout pass, and re-placed by [Placement] on every viewport
 * update. [zoom] is the integer zoom its layout properties were evaluated at -- upstream's
 * `bucket.zoom` -- and is *not* the zoom the map is at when it is drawn; that difference is exactly
 * what [SizeData] and [evaluateSizeForZoom] exist to bridge.
 *
 * A layer reading an overzoomed source is laid out once over the whole ancestor rather than once per
 * sub-square, as upstream lays out one bucket per canonical tile, so `mergeLines`, `clipLine`, the
 * line-anchor walk and `anchorIsTooClose` all see the geometry `symbol_layout.ts` would see.
 */
internal class SymbolBucket(
    val ref: TileRef,
    val layerIndex: Int,
    val layerId: String,
    val sourceName: String,
    val zoom: Int,
    val canvasSize: Int,
    val textSizeData: SizeData,
    val iconSizeData: SizeData,
    val instances: List<SymbolInstance>,
) {
    /** The cache and cross-tile identities key on this: the tile *fetched*, plus the style layer. */
    val key: String get() = "$sourceName-${ref.z}-${ref.x}-${ref.y}-s${ref.span}-L$layerIndex"

    /**
     * Upstream's `bucketInstanceId`, assigned by [CrossTileSymbolIndex] the first time it sees this
     * bucket. It is what tells a *rebuilt* bucket from the one already indexed at the same tile, and
     * what [CrossTileSymbolIndex] retires a stale index entry by.
     */
    var bucketInstanceId: Long = 0L
}
