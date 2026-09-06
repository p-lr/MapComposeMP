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

/**
 * Which side of the tile a point outside it lies on, as the `(dx, dy)` of the neighbour that owns
 * it. `(0, 0)` for a point the tile owns itself.
 */
fun tileDirectionOf(x: Double, y: Double, canvasSize: Int): Pair<Int, Int> {
    val dx = if (x < 0.0) -1 else if (x >= canvasSize) 1 else 0
    val dy = if (y < 0.0) -1 else if (y >= canvasSize) 1 else 0
    return dx to dy
}

/**
 * Which of a tile's circle vertices are drawn into it, once the neighbouring tiles are gathered.
 *
 * Upstream draws every circle into one viewport-wide framebuffer with `StencilMode.disabled`
 * (`draw_circle.ts`), so the tile that owns a point spills the whole disc over its neighbours'
 * ground. Here a tile is rasterized into its own bitmap and that bitmap is the clip, so the missing
 * half has to be drawn by the *neighbour* -- which means the neighbour has to have the point at all.
 * Two ways it can:
 *
 * - the source's MVT buffer duplicated the point into the neighbour's tile. Free, but bounded by the
 *   buffer: at the usual 64 units of a 4096 extent that is `canvasSize / 64` pixels, and a disc
 *   reaching further than that was drawn by nobody;
 * - the neighbouring tile is *gathered* and its own points are drawn into this one, offset by a
 *   tile. That is what [ovh.plrapps.mapcompose.vector.renderer.NeighbourTile] already does for the
 *   heatmap, and what removes the bound.
 *
 * So each vertex gets exactly one owner -- otherwise a translucent circle drawn from both its owner
 * and its buffered copy doubles its alpha along the seam. A vertex this tile owns ([isInsideTile],
 * half-open) is always drawn; one it does not is drawn only when the neighbour that owns it was
 * *not* gathered, which keeps the buffered-copy path as the fallback for a neighbour whose fetch
 * failed. A gathered neighbour therefore contributes owned vertices only ([forNeighbour]).
 *
 * @param offsetX where the tile these vertices were decoded in sits, in this tile's canvas space
 * @param coveredDirections the sides whose neighbour tile was gathered
 */
class CircleVertexGate(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    private val coveredDirections: Set<Pair<Int, Int>> = emptySet(),
) {
    fun accepts(x: Double, y: Double, canvasSize: Int, reach: Double): Boolean {
        if (!isInsideTile(x, y, canvasSize) &&
            tileDirectionOf(x, y, canvasSize) in coveredDirections
        ) {
            return false
        }
        return circleTouchesTile(x + offsetX, y + offsetY, canvasSize, reach)
    }

    companion object {
        /** Every side gathered, i.e. nothing but this tile's own vertices is drawn from it. */
        val ALL_DIRECTIONS: Set<Pair<Int, Int>> = buildSet {
            for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) add(dx to dy)
        }

        /** The gate for the tile `(dx, dy)` away: its own vertices, offset into this tile's space. */
        fun forNeighbour(dx: Int, dy: Int, canvasSize: Int): CircleVertexGate = CircleVertexGate(
            offsetX = dx.toDouble() * canvasSize,
            offsetY = dy.toDouble() * canvasSize,
            coveredDirections = ALL_DIRECTIONS,
        )
    }
}
