package ovh.plrapps.mapcompose.core

import androidx.compose.ui.unit.IntSize
import ovh.plrapps.mapcompose.utils.AngleRad

/**
 * Provides information about the current state of the viewport in a MapLibre-like map renderer.
 *
 * @property matrix The current tile transformation matrix, describing how map tiles are projected and positioned in the viewport.
 * @property size The pixel size (width and height) of the viewport.
 * @property angleRad The rotation angle of the viewport, in radians.
 * @property pitch The pitch (tilt) of the viewport, in degrees (0 = looking straight down).
 */
data class ViewportInfo(
    val matrix: TileMatrix,
    val size: IntSize,
    val angleRad: AngleRad,
    val pitch: Float,
)