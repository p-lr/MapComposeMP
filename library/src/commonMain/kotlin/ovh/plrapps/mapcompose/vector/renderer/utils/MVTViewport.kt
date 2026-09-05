package ovh.plrapps.mapcompose.vector.renderer.utils

import androidx.compose.ui.geometry.Rect

data class MVTViewport(
    val width: Float,
    val height: Float,
    val bearing: Float,
    val pitch: Float,
    val zoom: Float,
    val tileMatrix: Map<Int, IntRange>,
    /**
     * The wrap-around windows an infinite-scroll viewport also shows, kept apart from [tileMatrix].
     *
     * A `TileMatrix` is one *contiguous* column range per row, so a window at the far edge of the
     * world cannot be folded into one at the near edge without claiming everything between them --
     * the whole row. See `VectorLayer.VisibleMatrices`.
     */
    val overflowTileMatrices: List<Map<Int, IntRange>> = emptyList(),
)

val MVTViewport.bbox
    get() = Rect(left = 0f, top = 0f, right = width, bottom = height)