package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.maplibre.utils.obb.OBB
import ovh.plrapps.mapcompose.maplibre.utils.obb.ObbPoint

data class LabelPlacement(
    val text: String,
    val position: ObbPoint,
    val angle: Float,
    val bounds: Rect,
    val obb: OBB,
    val priority: Int,
    val allowOverlap: Boolean,
    val ignorePlacement: Boolean,
)
