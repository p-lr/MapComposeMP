package ovh.plrapps.mapcompose.vector.renderer.utils

/**
 * Whether a point belongs to the tile that carries it.
 *
 * Upstream's `CircleBucket.addFeature`: *"Do not include points that are outside the tile
 * boundaries."* An MVT layer is served with a buffer, so a point near an edge appears in both the
 * tile it belongs to and its neighbour. Without this rule it is drawn -- or accumulated -- twice,
 * which doubles a translucent circle's alpha along every seam and double-counts a heatmap's points.
 *
 * The bounds are half-open, so a point exactly on the right or bottom edge belongs to the next tile,
 * which is what keeps neighbours from both claiming it.
 */
fun isInsideTile(x: Double, y: Double, canvasSize: Int): Boolean =
    x >= 0.0 && x < canvasSize && y >= 0.0 && y < canvasSize

/**
 * Whether a circle of [radius] centred at ([x], [y]) reaches into the tile that carries the point.
 *
 * A **divergence from [isInsideTile], and a deliberate one.** Upstream can drop every point outside
 * the tile because it draws circles into one viewport-wide framebuffer with `StencilMode.disabled`
 * ("Allow kernels to be drawn across boundaries" is the same reasoning it states for the heatmap):
 * whichever tile owns the point draws the whole disc, spilling freely over its neighbours' ground.
 * Here a tile is rasterized into its own bitmap, and that bitmap is the clip -- so dropping the
 * point in the neighbour and clipping it in the owner left a disc straddling a seam chopped in
 * half, with neither tile drawing the missing part.
 *
 * Keeping a point whose disc reaches in costs nothing: both tiles draw it, each clipped to its own
 * bitmap, and their bitmaps cover disjoint ground -- which is already how a wide line or a polygon
 * crossing a seam is drawn. What must **not** use this rule is anything that *counts* points rather
 * than drawing them: the heatmap's accumulation and a symbol's anchor stay on [isInsideTile], where
 * a duplicate really would be counted twice.
 */
fun circleTouchesTile(x: Double, y: Double, canvasSize: Int, radius: Double): Boolean =
    x + radius >= 0.0 && x - radius < canvasSize && y + radius >= 0.0 && y - radius < canvasSize
