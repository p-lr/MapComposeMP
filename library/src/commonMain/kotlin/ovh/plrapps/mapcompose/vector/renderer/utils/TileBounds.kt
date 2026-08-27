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
