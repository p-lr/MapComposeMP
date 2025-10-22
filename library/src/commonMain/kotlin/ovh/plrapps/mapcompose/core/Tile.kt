package ovh.plrapps.mapcompose.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile

/**
 * A [Tile] is defined by its coordinates in the "pyramid". A [Tile] is sub-sampled when the
 * scale becomes lower than the scale of the lowest level. To reflect that, there is [subSample]
 * property which is a positive integer (can be 0). When [subSample] equals 0, the [bitmap] of the
 * tile is full scale. When [subSample] equals 1, the [bitmap] is sub-sampled and its size is half
 * the original bitmap (the one at the lowest level), and so on.
 */
internal data class Tile(
    val zoom: Int,
    val row: Int,
    val col: Int,
    val subSample: Int,
    val layerIds: List<String>,
    val opacities: List<Float>
) {
    @Volatile
    var bitmap: ImageBitmap? = null
    var alpha: Float by mutableFloatStateOf(0f)

    @Volatile
    var overlaps: Tile? = null

    @Volatile
    var markedForSweep = false   // write on main-thread only
}

internal data class TileSpec(val zoom: Int, val row: Int, val col: Int, val subSample: Int = 0)

internal fun Tile.spaceHash(): Int {
    return row + p1 * col + p1 * p2 * subSample + p1 * p2 * p3 * zoom
}

private const val p1 = 31
private const val p2 = 73
private const val p3 = 107
