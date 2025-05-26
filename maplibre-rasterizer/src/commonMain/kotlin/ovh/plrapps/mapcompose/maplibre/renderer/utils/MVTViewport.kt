package ovh.plrapps.mapcompose.maplibre.renderer.utils

import androidx.compose.ui.geometry.Rect

data class MVTViewport(
    val width: Float,
    val height: Float,
    val bearing: Float,
    val pitch: Float,
    val zoom: Float,
    val center: Float,
    val cameraToCenterDistance: Float,
    val tileMatrix: Map<Int, IntRange>
)

val MVTViewport.bbox
    get() = Rect(left = 0f, top = 0f, right = width, bottom = height)