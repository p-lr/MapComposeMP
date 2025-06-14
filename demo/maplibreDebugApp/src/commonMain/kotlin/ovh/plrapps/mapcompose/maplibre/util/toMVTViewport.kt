package ovh.plrapps.mapcompose.maplibre.util

import ovh.plrapps.mapcompose.core.ViewportInfo
import ovh.plrapps.mapcompose.maplibre.renderer.utils.MVTViewport

fun ViewportInfo.toMVTViewport() = MVTViewport(
    width = (this.size.width).toFloat(),
    height = (this.size.height).toFloat(),
    bearing = this.angleRad,
    pitch = this.pitch,
    zoom = this.zoom.toFloat(),
    tileMatrix = this.matrix
)