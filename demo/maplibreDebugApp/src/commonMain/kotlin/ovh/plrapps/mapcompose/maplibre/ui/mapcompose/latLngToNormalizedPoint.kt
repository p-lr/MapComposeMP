package ovh.plrapps.mapcompose.maplibre.ui.mapcompose

import ovh.plrapps.mapcompose.utils.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

fun Double.toRadians() = this * PI / 180.0

fun latLngToNormalizedPoint(lat: Double, lng: Double): Point {
    val x = (lng + 180.0) / 360.0
    val latRad = lat.toRadians()
    val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
    return Point(x = x, y = y)
}