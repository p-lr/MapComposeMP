package ovh.plrapps.mapcompose.vector.data.extension

import ovh.plrapps.mapcompose.core.ViewportInfo
import ovh.plrapps.mapcompose.vector.renderer.utils.MVTViewport

fun ViewportInfo.toMVTViewport() = MVTViewport(
    width = (this.size.width).toFloat(),
    height = (this.size.height).toFloat(),
    bearing = this.angleRad,
    pitch = this.pitch,
    zoom = this.zoom.toFloat(),
    tileMatrix = this.matrix
)