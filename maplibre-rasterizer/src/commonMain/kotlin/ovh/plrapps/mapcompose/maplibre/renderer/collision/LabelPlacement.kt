package ovh.plrapps.mapcompose.maplibre.renderer.collision

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

data class LabelPlacement(
    val text: String,
    val position: Pair<Float, Float>,
    val angle: Float,
    val bounds: Rect,
    val priority: Int,
    val allowOverlap: Boolean,
    val ignorePlacement: Boolean,
    val textLayoutResult: TextLayoutResult? = null
) 