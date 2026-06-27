package ovh.plrapps.mapcompose.vector.renderer.collision

import androidx.compose.ui.geometry.Rect
import ovh.plrapps.mapcompose.vector.utils.obb.OBB
import ovh.plrapps.mapcompose.vector.utils.obb.ObbPoint

data class LabelPlacement(
    val text: String,
    val position: ObbPoint,
    val angle: Float,
    val bounds: Rect,
    val obb: OBB,
    val layerIndex: Int,
    val inLayerPriority: Double,
    val overlapMode: OverlapMode,
    val ignorePlacement: Boolean,
)
