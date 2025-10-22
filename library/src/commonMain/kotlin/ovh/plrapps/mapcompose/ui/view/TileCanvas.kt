package ovh.plrapps.mapcompose.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import ovh.plrapps.mapcompose.core.ColorFilterProvider
import ovh.plrapps.mapcompose.core.Tile
import ovh.plrapps.mapcompose.core.VisibleTilesResolver
import ovh.plrapps.mapcompose.ui.layout.grid
import ovh.plrapps.mapcompose.ui.state.ZoomPanRotateState
import kotlin.math.ceil


@Composable
internal fun TileCanvas(
    modifier: Modifier,
    zoomPRState: ZoomPanRotateState,
    visibleTilesResolver: VisibleTilesResolver,
    tileSize: Int,
    alphaTick: Float,
    colorFilterProvider: ColorFilterProvider?,
    tilesToRender: List<Tile>,
    isFilteringBitmap: () -> Boolean,
) {
    val paint: PaintPlatform = remember {
        makePaintPlatform()
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
    ) {
        /* Scroll values may not be represented accurately using floats (a float has 7 significant
         * decimal digits, so any number above ~10M isn't represented accurately).
         * Since the translate function of the Canvas works with floats, we perform a change of
         * referential so that we only need to translate the canvas by an amount which can be
         * precisely represented as a float. */
        val x0 = ((ceil(zoomPRState.scrollX / grid) * grid) / zoomPRState.scale).toInt()
        val y0 = ((ceil(zoomPRState.scrollY / grid) * grid) / zoomPRState.scale).toInt()

        withTransform({
            /* Geometric transformations seem to be applied in reversed order of declaration */
            rotate(
                degrees = zoomPRState.rotation,
                pivot = Offset(
                    x = zoomPRState.pivotX.toFloat(),
                    y = zoomPRState.pivotY.toFloat()
                )
            )
            translate(
                left = (-zoomPRState.scrollX + x0 * zoomPRState.scale).toFloat(),
                top = (-zoomPRState.scrollY + y0 * zoomPRState.scale).toFloat()
            )
            scale(scale = zoomPRState.scale.toFloat(), Offset.Zero)
        }) {
            updateFilterBitmap(paint, isFilteringBitmap)

            for (tile in tilesToRender) {
                if (tile.markedForSweep) continue
                val bitmap = tile.bitmap ?: continue
                val scaleForLevel = visibleTilesResolver.getScaleForLevel(tile.zoom)
                    ?: continue
                val tileScaled = (tileSize / scaleForLevel).toInt()
                val l = tile.col * tileScaled
                val t = tile.row * tileScaled

                val colorFilter = colorFilterProvider?.getColorFilter(tile.row, tile.col, tile.zoom)

                setTilePaintProperties(paint, alpha = (tile.alpha * 255).toInt(), colorFilter)

                drawIntoCanvas {
                    drawTileIntoCanvas(it, bitmap, paint, l, t, tileScaled, x0, y0)
                }

                /* If a tile isn't fully opaque, increase its alpha state by the alpha tick */
                if (tile.alpha < 1f) {
                    tile.alpha = (tile.alpha + alphaTick).coerceAtMost(1f)
                } else {
                    tile.overlaps?.markedForSweep = true
                    tile.overlaps = null
                }
            }
        }
    }
}

expect class PaintPlatform

expect fun makePaintPlatform(): PaintPlatform
expect fun updateFilterBitmap(paintPlatform: PaintPlatform, isFilteringBitmap: () -> Boolean)
expect fun setTilePaintProperties(
    paintPlatform: PaintPlatform,
    alpha: Int,
    colorFilter: ColorFilter?
)

expect fun drawTileIntoCanvas(
    canvas: Canvas,
    bitmap: ImageBitmap,
    paintPlatform: PaintPlatform,
    l: Int,
    t: Int,
    tileScaled: Int,
    x0: Int,
    y0: Int
)